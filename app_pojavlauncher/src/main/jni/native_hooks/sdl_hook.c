#include "environ/environ.h"
#include "utils.h"
#include "native_hooks.h"
#include "log.h"
#include "SDL3/SDL.h"

#include <bytehook.h>
#include <dlfcn.h>
#include <jni.h>
#include <stdlib.h>
#include <string.h>
#include <pthread.h>
#include <stdbool.h>
#include <stdint.h>

DECL_DLSYM(SDL_InitSubSystem)
DECL_DLSYM(SDL_OpenGamepad)
DECL_DLSYM(SDL_OpenJoystick)
DECL_DLSYM(SDL_CloseGamepad)
DECL_DLSYM(SDL_CloseJoystick)
DECL_DLSYM(SDL_SetHint)
DECL_DLSYM(SDL_SetTextInputArea)
DECL_DLSYM(SDL_SetError)
DECL_DLSYM(SDL_GetError)
DECL_DLSYM(SDL_GetWindowFromEvent)
DECL_DLSYM(SDL_GetWindowFromID)
DECL_DLSYM(SDL_GL_SetAttribute)
DECL_DLSYM(SDL_GL_GetAttribute)
DECL_DLSYM(SDL_GL_LoadLibrary)
DECL_DLSYM(SDL_GL_GetProcAddress)
DECL_DLSYM(SDL_GL_UnloadLibrary)
DECL_DLSYM(SDL_LoadObject)
DECL_DLSYM(SDL_UnloadObject)
DECL_DLSYM(SDL_LoadFunction)
DECL_DLSYM(SDL_CreateWindow)
DECL_DLSYM(SDL_CreateWindowWithProperties)
DECL_DLSYM(SDL_DestroyWindow)
DECL_DLSYM(SDL_EGL_GetProcAddress)

typedef void *EGLDisplay;
typedef void *EGLConfig;
typedef int EGLint;
typedef int EGLBoolean;
typedef EGLBoolean (*eglChooseConfig_t)(EGLDisplay dpy, const EGLint *attrib_list, EGLConfig *configs,
                                        EGLint config_size, EGLint *num_config);
typedef void *(*eglCreateContext_t)(EGLDisplay dpy, EGLConfig config, void *share, const EGLint *attrib_list);
typedef EGLBoolean (*eglSwapBuffers_t)(EGLDisplay dpy, void *surface);

#define EGL_NONE                      0x3038
#define EGL_RENDERABLE_TYPE           0x3040
#define EGL_OPENGL_BIT                0x0008
#define EGL_OPENGL_ES2_BIT            0x0004
#define EGL_OPENGL_ES3_BIT            0x0040
#define EGL_CONTEXT_CLIENT_VERSION    0x3098
#define EGL_CONTEXT_MAJOR_VERSION_KHR 0x30FB
#define EGL_CONTEXT_MINOR_VERSION_KHR 0x30FC

// --- SDL window event resolution fallback ---
static SDL_Window *sdlLastEventWindow = NULL;

static SDL_Window *custom_SDL_GetWindowFromEvent_Func(const SDL_Event *event) {
    SDL_Window *window = BYTEHOOK_CALL_PREV(custom_SDL_GetWindowFromEvent_Func, SDL_GetWindowFromEvent_t, event);
    if (window != NULL) {
        sdlLastEventWindow = window;
    } else if (sdlLastEventWindow != NULL) {
        window = sdlLastEventWindow;
    }
    BYTEHOOK_POP_STACK();
    return window;
}

static SDL_Window *custom_SDL_GetWindowFromID_Func(SDL_WindowID id) {
    SDL_Window *window = BYTEHOOK_CALL_PREV(custom_SDL_GetWindowFromID_Func, SDL_GetWindowFromID_t, id);
    if (window != NULL) {
        sdlLastEventWindow = window;
    } else if (sdlLastEventWindow != NULL) {
        window = sdlLastEventWindow;
    }
    BYTEHOOK_POP_STACK();
    return window;
}

// --- EGL config/context normalization for mobile ES renderers ---
static EGLBoolean normalizeEglChooseConfigList(const EGLint *attrib_list, EGLint *fixed, int cap) {
    if (attrib_list == NULL) return 0;
    int n = 0;
    for (int i = 0; n < cap - 2; i += 2) {
        EGLint attr = attrib_list[i];
        EGLint val = attrib_list[i + 1];
        if (attr == EGL_NONE) {
            fixed[n] = EGL_NONE;
            fixed[n + 1] = 0;
            n += 2;
            break;
        }
        if (attr == EGL_RENDERABLE_TYPE) {
            if ((val & (EGL_OPENGL_ES3_BIT | EGL_OPENGL_BIT)) != 0 && (val & EGL_OPENGL_ES2_BIT) == 0) {
                val = (val & ~(EGL_OPENGL_ES3_BIT | EGL_OPENGL_BIT)) | EGL_OPENGL_ES2_BIT;
            }
        }
        fixed[n] = attr;
        fixed[n + 1] = val;
        n += 2;
    }
    return n > 0;
}

static int normalizeEglContextAttribs(const EGLint *attrib_list, EGLint *fixed, int cap, bool esSemantics) {
    int version = 0;
    bool hasClientVersion = false;
    if (attrib_list == NULL) return 0;
    int n = 0;
    for (int i = 0; n < cap - 2; i += 2) {
        EGLint attr = attrib_list[i];
        EGLint val = attrib_list[i + 1];
        if (attr == EGL_NONE) break;
        if (attr == EGL_CONTEXT_MAJOR_VERSION_KHR) {
            if (version == 0) version = val;
            continue;
        }
        if (attr == EGL_CONTEXT_MINOR_VERSION_KHR) continue;
        if (attr == EGL_CONTEXT_CLIENT_VERSION) {
            hasClientVersion = true;
            if (version == 0) version = val;
        }
        if (n >= cap - 2) return 0;
        fixed[n++] = attr;
        fixed[n++] = val;
    }
    if (esSemantics && version > 0 && !hasClientVersion) {
        if (n >= cap - 2) return 0;
        fixed[n++] = EGL_CONTEXT_CLIENT_VERSION;
        fixed[n++] = version;
    }
    if (n >= cap - 2) return 0;
    fixed[n++] = EGL_NONE;
    fixed[n++] = 0;
    return version;
}

static bool isMobileGluesEgl(void) {
    const char *egl = getenv("POJAVEXEC_EGL");
    if (egl == NULL) return false;
    const char *base = strrchr(egl, '/');
    return strcmp(base != NULL ? base + 1 : egl, "libmobileglues.so") == 0;
}

static bool sdlGlesCompatEnabled(void) {
    const char *renderer = getenv("AMETHYST_RENDERER");
    if (renderer == NULL) renderer = getenv("POJAV_RENDERER");
    if (renderer == NULL) return isMobileGluesEgl();
    if (strstr(renderer, "desktopgl") != NULL) return false;
    if (strncmp(renderer, "gallium_", 8) == 0) return false;
    if (strcmp(renderer, "custom_gallium") == 0 || strcmp(renderer, "vulkan_zink") == 0) return false;
    if (strncmp(renderer, "opengles", 8) == 0) return true;
    return isMobileGluesEgl();
}

static bool sForcedEsProfile = false;

static bool shouldReusePrimaryWindow(void) {
    const char *value = getenv("POJAV_SDL_REUSE_WINDOW");
    if (value != NULL) return strcmp(value, "1") == 0;
    return true;
}

static eglChooseConfig_t sOrigEglChooseConfig = NULL;
static eglCreateContext_t sOrigEglCreateContext = NULL;
static eglSwapBuffers_t sOrigEglSwapBuffers = NULL;

static void *proxyEglCreateContext(EGLDisplay dpy, EGLConfig config, void *share, const EGLint *attrib_list) {
    if (sOrigEglCreateContext == NULL) {
        LOGE("SDL_Hook: eglCreateContext was not resolved");
        return NULL;
    }

    void *ctx = sOrigEglCreateContext(dpy, config, share, attrib_list);
    if (ctx != NULL || !sdlGlesCompatEnabled()) return ctx;

    bool esSemantics = sForcedEsProfile;
    EGLint fixed[64];
    int version = normalizeEglContextAttribs(attrib_list, fixed, 64, esSemantics);
    if (version == 0) return ctx;

    LOGW("SDL_Hook: retrying eglCreateContext without KHR version attrs (CV=%d)", version);
    ctx = sOrigEglCreateContext(dpy, config, share, fixed);
    if (ctx != NULL || !esSemantics || version <= 2) return ctx;

    LOGW("SDL_Hook: retrying eglCreateContext with CV=2 after CV=%d failed", version);
    EGLint es2[3] = {EGL_CONTEXT_CLIENT_VERSION, 2, EGL_NONE};
    return sOrigEglCreateContext(dpy, config, share, es2);
}

static EGLBoolean proxyEglChooseConfig(EGLDisplay dpy, const EGLint *attrib_list, EGLConfig *configs,
                                       EGLint config_size, EGLint *num_config) {
    if (sOrigEglChooseConfig == NULL) {
        LOGE("SDL_Hook: eglChooseConfig was not resolved");
        return 0;
    }

    EGLBoolean result = sOrigEglChooseConfig(dpy, attrib_list, configs, config_size, num_config);
    if (result && num_config != NULL && *num_config > 0) return result;
    if (!sdlGlesCompatEnabled()) return result;

    EGLint fixed[64];
    if (!normalizeEglChooseConfigList(attrib_list, fixed, 64)) return result;
    EGLint fallbackCount = 0;
    EGLBoolean fallbackResult = sOrigEglChooseConfig(dpy, fixed, configs, config_size, &fallbackCount);
    if (fallbackResult && num_config != NULL) *num_config = fallbackCount;
    LOGW("SDL_Hook: eglChooseConfig compatibility fallback result=%d count=%d", fallbackResult, fallbackCount);
    return fallbackResult;
}

static EGLBoolean proxyEglSwapBuffers(EGLDisplay dpy, void *surface) {
    if (sOrigEglSwapBuffers == NULL) {
        LOGE("SDL_Hook: eglSwapBuffers was not resolved");
        return 0;
    }
    return sOrigEglSwapBuffers(dpy, surface);
}

static void *injectEglProxy(const char *name, void *resolved) {
    if (name == NULL) return resolved;
    if (strcmp(name, "eglChooseConfig") == 0) {
        if (resolved != NULL && sOrigEglChooseConfig == NULL) sOrigEglChooseConfig = (eglChooseConfig_t) resolved;
        if (sOrigEglChooseConfig != NULL && resolved != (void *) proxyEglChooseConfig) resolved = (void *) proxyEglChooseConfig;
    } else if (strcmp(name, "eglCreateContext") == 0) {
        if (resolved != NULL && sOrigEglCreateContext == NULL) sOrigEglCreateContext = (eglCreateContext_t) resolved;
        if (sOrigEglCreateContext != NULL && resolved != (void *) proxyEglCreateContext) resolved = (void *) proxyEglCreateContext;
    } else if (strcmp(name, "eglSwapBuffers") == 0) {
        if (resolved != NULL && sOrigEglSwapBuffers == NULL) sOrigEglSwapBuffers = (eglSwapBuffers_t) resolved;
        if (sOrigEglSwapBuffers != NULL && resolved != (void *) proxyEglSwapBuffers) resolved = (void *) proxyEglSwapBuffers;
    }
    return resolved;
}

static SDL_FunctionPointer custom_SDL_LoadFunction_Func(SDL_SharedObject *handle, const char *name) {
    SDL_FunctionPointer r = BYTEHOOK_CALL_PREV(custom_SDL_LoadFunction_Func, SDL_LoadFunction_t, handle, name);
    BYTEHOOK_POP_STACK();
    return (SDL_FunctionPointer) injectEglProxy(name, (void *) r);
}

static SDL_FunctionPointer custom_SDL_EGLGetProcAddress_Func(const char *proc) {
    SDL_FunctionPointer r = BYTEHOOK_CALL_PREV(custom_SDL_EGLGetProcAddress_Func, SDL_EGL_GetProcAddress_t, proc);
    BYTEHOOK_POP_STACK();
    if (proc == NULL || r == NULL) return r;
    return (SDL_FunctionPointer) injectEglProxy(proc, (void *) r);
}

// --- Vulkan loader consistency hook ---
static SDL_SharedObject *custom_SDL_LoadObject_Func(const char *path) {
    if (path != NULL && strstr(path, "libvulkan") != NULL) {
        const char *vkptr = getenv("VULKAN_PTR");
        if (vkptr != NULL && vkptr[0] != '\0') {
            void *handle = (void *) (uintptr_t) strtoull(vkptr, NULL, 16);
            if (handle != NULL) return (SDL_SharedObject *) handle;
        }
    }
    SDL_SharedObject *r = BYTEHOOK_CALL_PREV(custom_SDL_LoadObject_Func, SDL_LoadObject_t, path);
    BYTEHOOK_POP_STACK();
    return r;
}

static void custom_SDL_UnloadObject_Func(SDL_SharedObject *handle) {
    const char *vkptr = getenv("VULKAN_PTR");
    if (vkptr != NULL && vkptr[0] != '\0') {
        void *vulkan_handle = (void *) (uintptr_t) strtoull(vkptr, NULL, 16);
        if ((void *) handle == vulkan_handle) return;
    }
    BYTEHOOK_CALL_PREV(custom_SDL_UnloadObject_Func, SDL_UnloadObject_t, handle);
    BYTEHOOK_POP_STACK();
}

// --- Primary window reuse & lifetime tracking ---
static SDL_Window *sPrimaryWindow = NULL;
static unsigned int sPrimaryWindowRefs = 0;

static bool releasePrimaryWindow(SDL_Window *window) {
    if (window == sPrimaryWindow) {
        if (sPrimaryWindowRefs > 0) {
            sPrimaryWindowRefs--;
            LOGI("SDL_Hook: releasing logical window %p, refs=%u", window, sPrimaryWindowRefs);
            if (sPrimaryWindowRefs > 0) {
                if (window == sdlLastEventWindow) sdlLastEventWindow = NULL;
                return false;
            }
            sPrimaryWindow = NULL;
        }
        if (window == sdlLastEventWindow) sdlLastEventWindow = NULL;
    } else if (window == sdlLastEventWindow) {
        sdlLastEventWindow = NULL;
    }
    return true;
}

static void custom_SDL_DestroyWindow_Func(SDL_Window *window) {
    if (releasePrimaryWindow(window)) {
        BYTEHOOK_CALL_PREV(custom_SDL_DestroyWindow_Func, SDL_DestroyWindow_t, window);
    }
    BYTEHOOK_POP_STACK();
}

static void forceEglProfileEs(void) {
    if (!sdlGlesCompatEnabled()) return;
    SET_DLSYM_PTR(dlopen("libSDL3.so", RTLD_NOLOAD), SDL_GL_SetAttribute);
    if (SDL_GL_SetAttribute_p) {
        SDL_GL_SetAttribute_p(SDL_GL_CONTEXT_PROFILE_MASK, SDL_GL_CONTEXT_PROFILE_ES);
        sForcedEsProfile = true;
    }
}

static SDL_Window *reusePrimaryWindow(void) {
    ++sPrimaryWindowRefs;
    LOGI("SDL_Hook: reusing primary window %p, refs=%u", sPrimaryWindow, sPrimaryWindowRefs);
    return sPrimaryWindow;
}

static SDL_Window *custom_SDL_CreateWindow_Func(const char *title, int w, int h, SDL_WindowFlags flags) {
    forceEglProfileEs();
    const bool reuse = shouldReusePrimaryWindow();
    LOGI("SDL_Hook: primary window reuse=%s", reuse ? "enabled" : "disabled");
    if (reuse && sPrimaryWindow != NULL) {
        return reusePrimaryWindow();
    }
    SDL_Window *wnd = BYTEHOOK_CALL_PREV(custom_SDL_CreateWindow_Func, SDL_CreateWindow_t, title, w, h, flags);
    if (reuse && wnd != NULL) {
        sPrimaryWindow = wnd;
        sPrimaryWindowRefs = 1;
    }
    BYTEHOOK_POP_STACK();
    return wnd;
}

static SDL_Window *custom_SDL_CreateWindowWithProperties_Func(SDL_PropertiesID props) {
    forceEglProfileEs();
    const bool reuse = shouldReusePrimaryWindow();
    LOGI("SDL_Hook: primary window reuse=%s", reuse ? "enabled" : "disabled");
    if (reuse && sPrimaryWindow != NULL) {
        return reusePrimaryWindow();
    }
    SDL_Window *wnd = BYTEHOOK_CALL_PREV(custom_SDL_CreateWindowWithProperties_Func, SDL_CreateWindowWithProperties_t, props);
    if (reuse && wnd != NULL) {
        sPrimaryWindow = wnd;
        sPrimaryWindowRefs = 1;
    }
    BYTEHOOK_POP_STACK();
    return wnd;
}

static bool sdlInitSubSystemPrepare(SDL_InitFlags flags) {
    TRY_ATTACH_ENV(dvm_env, pojav_environ->dalvikJavaVMPtr, "SDL_InitSubSystem failed!",
            SET_DLSYM_PTR(dlopen("libSDL3.so", RTLD_NOLOAD), SDL_SetError);
            if (SDL_SetError_p) SDL_SetError_p("Failed to load SDL launcher integration android-side. This is not an SDL bug, please contact the launcher developer.");
            return false;
            );

    jint safeFlags;
    if (flags > INT32_MAX) {
        safeFlags = -1;
    } else safeFlags = (jint)flags;

    notifyLauncher(dvm_env, NOTIF_TYPE_SDL, (int[]){ACTION_INIT_LAUNCHER_INTEGRATION, safeFlags}, 2);

    SET_DLSYM_PTR(dlopen("libSDL3.so", RTLD_NOLOAD), SDL_SetHint);
    if (SDL_SetHint_p) SDL_SetHint_p("SDL_RETURN_KEY_HIDES_IME", "true");
    if (isMobileGluesEgl()) {
        if (SDL_SetHint_p) SDL_SetHint_p("SDL_OPENGL_FORCE_SRGB_FRAMEBUFFER", "0");
    }
    if (SDL_SetHint_p) SDL_SetHint_p("SDL_ENABLE_SCREEN_KEYBOARD", "1");
    return true;
}

static bool custom_SDL_InitSubSystem_Func(SDL_InitFlags flags) {
    if (!sdlInitSubSystemPrepare(flags)) return false;

    bool r = BYTEHOOK_CALL_PREV(custom_SDL_InitSubSystem_Func, SDL_InitSubSystem_t, flags);
    if (!r){
        SET_DLSYM_PTR(dlopen("libSDL3.so", RTLD_NOLOAD), SDL_GetError);
        LOGI("Deepslate: SDL_InitSubsystem Error: %s", SDL_GetError_p());
    }
    BYTEHOOK_POP_STACK();
    return r;
}

// --- OpenGL / MobileGlues proc address & library hooks ---
static void *sLoadedGlHandle = NULL;

static bool proxy_SDL_GL_LoadLibrary(const char *path) {
    LOGI("SDL_Hook: proxy_SDL_GL_LoadLibrary(%s)", path ? path : "NULL");
    if (path != NULL && path[0] != '\0') {
        void *h = dlopen(path, RTLD_LOCAL | RTLD_NOW);
        if (h != NULL) {
            sLoadedGlHandle = h;
            LOGI("SDL_Hook: SDL_GL_LoadLibrary loaded handle %p from %s", h, path);
        } else {
            LOGW("SDL_Hook: SDL_GL_LoadLibrary failed to dlopen(%s): %s", path, dlerror());
        }
    }
    if (sLoadedGlHandle == NULL) {
        const char *gl_lib = getenv("SDL_OPENGL_LIBRARY");
        if (gl_lib != NULL && gl_lib[0] != '\0') {
            sLoadedGlHandle = dlopen(gl_lib, RTLD_LOCAL | RTLD_NOW);
        }
        if (sLoadedGlHandle == NULL) {
            const char *egl = getenv("POJAVEXEC_EGL");
            if (egl != NULL) {
                sLoadedGlHandle = dlopen(egl, RTLD_LOCAL | RTLD_NOW);
            }
        }
        if (sLoadedGlHandle == NULL) {
            sLoadedGlHandle = dlopen("libmobileglues.so", RTLD_LOCAL | RTLD_NOW);
        }
    }
    return true;
}

static bool custom_SDL_GL_LoadLibrary_Func(const char *path) {
    bool r = proxy_SDL_GL_LoadLibrary(path);
    BYTEHOOK_POP_STACK();
    return r;
}

static SDL_FunctionPointer proxy_SDL_GL_GetProcAddress(const char *proc) {
    if (proc != NULL) {
        // Priority 1: Check the library explicitly loaded by SDL_GL_LoadLibrary (matches GL.getFunctionProvider())
        if (sLoadedGlHandle != NULL) {
            void *sym = dlsym(sLoadedGlHandle, proc);
            if (sym != NULL) {
                LOGI("SDL_Hook: SDL_GL_GetProcAddress(%s) -> %p (sLoadedGlHandle)", proc, sym);
                return (SDL_FunctionPointer) sym;
            }
        }
        // Priority 2: Check MobileGlues directly if handle not set yet
        void *mg = dlopen("libmobileglues.so", RTLD_NOLOAD);
        if (mg != NULL) {
            void *sym = dlsym(mg, proc);
            if (sym != NULL) {
                LOGI("SDL_Hook: SDL_GL_GetProcAddress(%s) -> %p (libmobileglues.so)", proc, sym);
                return (SDL_FunctionPointer) sym;
            }
        }
        // Priority 3: Fallback to eglGetProcAddress
        void *egl_handle = dlopen("libmobileglues.so", RTLD_NOLOAD);
        if (egl_handle == NULL) egl_handle = dlopen("libEGL.so", RTLD_NOLOAD);
        if (egl_handle != NULL) {
            typedef void *(*eglGetProcAddress_fn)(const char *);
            eglGetProcAddress_fn eglGpa = (eglGetProcAddress_fn) dlsym(egl_handle, "eglGetProcAddress");
            if (eglGpa != NULL) {
                void *egl_sym = eglGpa(proc);
                if (egl_sym != NULL) {
                    LOGI("SDL_Hook: SDL_GL_GetProcAddress(%s) -> %p (eglGetProcAddress)", proc, egl_sym);
                    return (SDL_FunctionPointer) egl_sym;
                }
            }
        }
    }
    return NULL;
}

static SDL_FunctionPointer custom_SDL_GL_GetProcAddress_Func(const char *proc) {
    SDL_FunctionPointer sym = proxy_SDL_GL_GetProcAddress(proc);
    BYTEHOOK_POP_STACK();
    return sym;
}

static bool proxy_SDL_GL_SetAttribute(SDL_GLAttr attr, int value) {
    if (attr == SDL_GL_CONTEXT_PROFILE_MASK && sdlGlesCompatEnabled()) {
        LOGI("SDL_Hook: overriding SDL_GL_CONTEXT_PROFILE_MASK %d -> SDL_GL_CONTEXT_PROFILE_ES (4)", value);
        value = SDL_GL_CONTEXT_PROFILE_ES;
        sForcedEsProfile = true;
    }
    return true;
}

static bool custom_SDL_GL_SetAttribute_Func(SDL_GLAttr attr, int value) {
    if (attr == SDL_GL_CONTEXT_PROFILE_MASK && sdlGlesCompatEnabled()) {
        LOGI("SDL_Hook: custom overriding SDL_GL_CONTEXT_PROFILE_MASK %d -> SDL_GL_CONTEXT_PROFILE_ES (4)", value);
        value = SDL_GL_CONTEXT_PROFILE_ES;
        sForcedEsProfile = true;
    }
    bool r = BYTEHOOK_CALL_PREV(custom_SDL_GL_SetAttribute_Func, SDL_GL_SetAttribute_t, attr, value);
    BYTEHOOK_POP_STACK();
    return r;
}

static bool proxy_SDL_GL_GetAttribute(SDL_GLAttr attr, int *value) {
    if (value != NULL && sdlGlesCompatEnabled()) {
        if (attr == SDL_GL_CONTEXT_MAJOR_VERSION && *value < 3) {
            LOGI("SDL_Hook: proxy boosting reported GL major version %d -> 4", *value);
            *value = 4;
            return true;
        } else if (attr == SDL_GL_CONTEXT_MINOR_VERSION && *value < 0) {
            *value = 0;
            return true;
        } else if (attr == SDL_GL_CONTEXT_PROFILE_MASK) {
            *value = SDL_GL_CONTEXT_PROFILE_ES;
            return true;
        }
    }
    return true;
}

static bool custom_SDL_GL_GetAttribute_Func(SDL_GLAttr attr, int *value) {
    bool r = BYTEHOOK_CALL_PREV(custom_SDL_GL_GetAttribute_Func, SDL_GL_GetAttribute_t, attr, value);
    BYTEHOOK_POP_STACK();
    if (value != NULL && sdlGlesCompatEnabled()) {
        if (attr == SDL_GL_CONTEXT_MAJOR_VERSION && *value < 3) {
            *value = 4;
            r = true;
        } else if (attr == SDL_GL_CONTEXT_MINOR_VERSION && *value < 0) {
            *value = 0;
            r = true;
        } else if (attr == SDL_GL_CONTEXT_PROFILE_MASK) {
            *value = SDL_GL_CONTEXT_PROFILE_ES;
            r = true;
        }
    }
    return r;
}

static void proxy_SDL_GL_UnloadLibrary(void) {
    LOGI("SDL_Hook: proxy_SDL_GL_UnloadLibrary called");
}

static void custom_SDL_GL_UnloadLibrary_Func(void) {
    proxy_SDL_GL_UnloadLibrary();
    BYTEHOOK_POP_STACK();
}

/*
 * Tracked handle lists
 */
typedef struct joystick_node {
    SDL_Joystick *handle;
    struct joystick_node *next;
} joystick_node_t;

typedef struct gamepad_node {
    SDL_Gamepad *handle;
    struct gamepad_node *next;
} gamepad_node_t;

static pthread_mutex_t g_sdl_handles_mutex =
        PTHREAD_MUTEX_INITIALIZER;

static joystick_node_t *g_joysticks = NULL;
static gamepad_node_t *g_gamepads = NULL;

static int g_joystick_count = 0;
static int g_gamepad_count = 0;
static int g_active_sdl_device_count = 0;

static void on_sdl_input_active(void)
{
    LOGI("SDL controller integration active");
    TRY_ATTACH_ENV(dvm_env, pojav_environ->dalvikJavaVMPtr, "SDL_InitSubSystem failed!",
            SET_DLSYM_PTR(dlopen("libSDL3.so", RTLD_NOLOAD), SDL_SetError);
            if (SDL_SetError_p) SDL_SetError_p("Failed to load SDL launcher integration android-side. This is not an SDL bug, please contact the launcher developer.");
            return;
    );
    notifyLauncher(dvm_env, NOTIF_TYPE_SDL, (int[]){ACTION_INIT_CONTROLLER}, 1);
}

static void on_sdl_input_inactive(void)
{
    LOGI("SDL controller integration inactive");
    TRY_ATTACH_ENV(dvm_env, pojav_environ->dalvikJavaVMPtr, "SDL_InitSubSystem failed!",
            SET_DLSYM_PTR(dlopen("libSDL3.so", RTLD_NOLOAD), SDL_SetError);
            if (SDL_SetError_p) SDL_SetError_p("Failed to load SDL launcher integration android-side. This is not an SDL bug, please contact the launcher developer.");
            return;
    );
    notifyLauncher(dvm_env, NOTIF_TYPE_SDL, (int[]){ACTION_DEINIT_CONTROLLER}, 1);
}

static void track_joystick(SDL_Joystick *joystick)
{
    joystick_node_t *node;
    int became_active = 0;

    if (joystick == NULL)
        return;

    node = malloc(sizeof(*node));
    if (node == NULL) {
        LOGI("Failed to allocate joystick tracking node");
        return;
    }

    node->handle = joystick;

    pthread_mutex_lock(&g_sdl_handles_mutex);

    node->next = g_joysticks;
    g_joysticks = node;

    g_joystick_count++;

    if (g_active_sdl_device_count == 0)
        became_active = 1;

    g_active_sdl_device_count++;

    pthread_mutex_unlock(&g_sdl_handles_mutex);

    if (became_active)
        on_sdl_input_active();
}

static void untrack_joystick(SDL_Joystick *joystick)
{
    joystick_node_t **current;
    int became_inactive = 0;

    if (joystick == NULL)
        return;

    pthread_mutex_lock(&g_sdl_handles_mutex);

    current = &g_joysticks;

    while (*current != NULL) {
        if ((*current)->handle == joystick) {
            joystick_node_t *removed = *current;

            *current = removed->next;
            free(removed);

            if (g_joystick_count > 0)
                g_joystick_count--;

            if (g_active_sdl_device_count > 0)
                g_active_sdl_device_count--;

            if (g_active_sdl_device_count == 0)
                became_inactive = 1;

            break;
        }

        current = &(*current)->next;
    }

    pthread_mutex_unlock(&g_sdl_handles_mutex);

    if (became_inactive)
        on_sdl_input_inactive();
}

static void track_gamepad(SDL_Gamepad *gamepad)
{
    gamepad_node_t *node;
    int became_active = 0;

    if (gamepad == NULL)
        return;

    node = malloc(sizeof(*node));
    if (node == NULL) {
        LOGI("Failed to allocate gamepad tracking node");
        return;
    }

    node->handle = gamepad;

    pthread_mutex_lock(&g_sdl_handles_mutex);

    node->next = g_gamepads;
    g_gamepads = node;

    g_gamepad_count++;

    if (g_active_sdl_device_count == 0)
        became_active = 1;

    g_active_sdl_device_count++;

    pthread_mutex_unlock(&g_sdl_handles_mutex);

    if (became_active)
        on_sdl_input_active();
}

static void untrack_gamepad(SDL_Gamepad *gamepad)
{
    gamepad_node_t **current;
    int became_inactive = 0;

    if (gamepad == NULL)
        return;

    pthread_mutex_lock(&g_sdl_handles_mutex);

    current = &g_gamepads;

    while (*current != NULL) {
        if ((*current)->handle == gamepad) {
            gamepad_node_t *removed = *current;

            *current = removed->next;
            free(removed);

            if (g_gamepad_count > 0)
                g_gamepad_count--;

            if (g_active_sdl_device_count > 0)
                g_active_sdl_device_count--;

            if (g_active_sdl_device_count == 0)
                became_inactive = 1;

            break;
        }

        current = &(*current)->next;
    }

    pthread_mutex_unlock(&g_sdl_handles_mutex);

    if (became_inactive)
        on_sdl_input_inactive();
}

static SDL_Joystick *
custom_SDL_OpenJoystick_Func(SDL_JoystickID instance_id)
{
    LOGI("SDL_OpenJoystick(%d)", (int)instance_id);

    SDL_Joystick *result = BYTEHOOK_CALL_PREV(
            custom_SDL_OpenJoystick_Func,
            SDL_OpenJoystick_t,
            instance_id
    );

    BYTEHOOK_POP_STACK();

    if (result != NULL) {
        track_joystick(result);
        LOGI("Joystick opened: %p, active devices: %d", result, g_active_sdl_device_count);
    }

    return result;
}

static SDL_Gamepad *
custom_SDL_OpenGamepad_Func(SDL_JoystickID instance_id)
{
    LOGI("SDL_OpenGamepad(%d)", (int)instance_id);

    SDL_Gamepad *result = BYTEHOOK_CALL_PREV(
            custom_SDL_OpenGamepad_Func,
            SDL_OpenGamepad_t,
            instance_id
    );

    BYTEHOOK_POP_STACK();

    if (result != NULL) {
        track_gamepad(result);
        LOGI("Gamepad opened: %p, active devices: %d", result, g_active_sdl_device_count);
    }

    return result;
}

static void
custom_SDL_CloseJoystick_Func(SDL_Joystick *joystick)
{
    LOGI("SDL_CloseJoystick(%p)", joystick);

    BYTEHOOK_CALL_PREV(
            custom_SDL_CloseJoystick_Func,
            SDL_CloseJoystick_t,
            joystick
    );

    BYTEHOOK_POP_STACK();

    untrack_joystick(joystick);

    LOGI("Joystick closed: %p, active devices: %d", joystick, g_active_sdl_device_count);
}

static void
custom_SDL_CloseGamepad_Func(SDL_Gamepad *gamepad)
{
    LOGI("SDL_CloseGamepad(%p)", gamepad);

    BYTEHOOK_CALL_PREV(
            custom_SDL_CloseGamepad_Func,
            SDL_CloseGamepad_t,
            gamepad
    );

    BYTEHOOK_POP_STACK();

    untrack_gamepad(gamepad);

    LOGI("Gamepad closed: %p, active devices: %d", gamepad, g_active_sdl_device_count);
}

// ---------- dlsym proxy layer ----------
static SDL_InitSubSystem_t realSdlInitSubSystem = NULL;
static SDL_CreateWindow_t realSdlCreateWindow = NULL;
static SDL_CreateWindowWithProperties_t realSdlCreateWindowWithProperties = NULL;
static SDL_DestroyWindow_t realSdlDestroyWindow = NULL;
static SDL_GetWindowFromEvent_t realSdlGetWindowFromEvent = NULL;
static SDL_GetWindowFromID_t realSdlGetWindowFromID = NULL;
static SDL_LoadObject_t realSdlLoadObject = NULL;
static SDL_LoadFunction_t realSdlLoadFunction = NULL;
static SDL_UnloadObject_t realSdlUnloadObject = NULL;

static bool proxy_SDL_InitSubSystem(SDL_InitFlags flags) {
    if (!sdlInitSubSystemPrepare(flags)) return false;
    bool r = realSdlInitSubSystem ? realSdlInitSubSystem(flags) : true;
    if (!r) {
        SET_DLSYM_PTR(dlopen("libSDL3.so", RTLD_NOLOAD), SDL_GetError);
        LOGE("SDL_Hook: SDL_InitSubsystem Error: %s", SDL_GetError_p());
    }
    return r;
}

static SDL_Window *proxy_SDL_CreateWindow(const char *title, int w, int h, SDL_WindowFlags flags) {
    forceEglProfileEs();
    if (shouldReusePrimaryWindow() && sPrimaryWindow != NULL) return reusePrimaryWindow();
    SDL_Window *window = realSdlCreateWindow ? realSdlCreateWindow(title, w, h, flags) : NULL;
    if (window != NULL && shouldReusePrimaryWindow()) {
        sPrimaryWindow = window;
        sPrimaryWindowRefs = 1;
    }
    return window;
}

static SDL_Window *proxy_SDL_CreateWindowWithProperties(SDL_PropertiesID props) {
    forceEglProfileEs();
    if (shouldReusePrimaryWindow() && sPrimaryWindow != NULL) return reusePrimaryWindow();
    SDL_Window *window = realSdlCreateWindowWithProperties ? realSdlCreateWindowWithProperties(props) : NULL;
    if (window != NULL && shouldReusePrimaryWindow()) {
        sPrimaryWindow = window;
        sPrimaryWindowRefs = 1;
    }
    return window;
}

static void proxy_SDL_DestroyWindow(SDL_Window *window) {
    if (releasePrimaryWindow(window) && realSdlDestroyWindow != NULL) {
        realSdlDestroyWindow(window);
    }
}

static SDL_Window *proxy_SDL_GetWindowFromEvent(const SDL_Event *event) {
    SDL_Window *window = realSdlGetWindowFromEvent ? realSdlGetWindowFromEvent(event) : NULL;
    if (window != NULL) {
        sdlLastEventWindow = window;
    } else if (sdlLastEventWindow != NULL) {
        window = sdlLastEventWindow;
    }
    return window;
}

static SDL_Window *proxy_SDL_GetWindowFromID(SDL_WindowID id) {
    SDL_Window *window = realSdlGetWindowFromID ? realSdlGetWindowFromID(id) : NULL;
    if (window != NULL) {
        sdlLastEventWindow = window;
    } else if (sdlLastEventWindow != NULL) {
        window = sdlLastEventWindow;
    }
    return window;
}

static SDL_SharedObject *proxy_SDL_LoadObject(const char *path) {
    if (path != NULL && strstr(path, "libvulkan") != NULL) {
        const char *vkptr = getenv("VULKAN_PTR");
        if (vkptr != NULL && vkptr[0] != '\0') {
            void *handle = (void *) (uintptr_t) strtoull(vkptr, NULL, 16);
            if (handle != NULL) return (SDL_SharedObject *) handle;
        }
    }
    return realSdlLoadObject ? realSdlLoadObject(path) : (SDL_SharedObject *) dlopen(path, RTLD_LOCAL | RTLD_NOW);
}

static SDL_FunctionPointer proxy_SDL_LoadFunction(SDL_SharedObject *handle, const char *name) {
    void *resolved = realSdlLoadFunction ? (void *) realSdlLoadFunction(handle, name) : dlsym((void *) handle, name);
    return (SDL_FunctionPointer) injectEglProxy(name, resolved);
}

static void proxy_SDL_UnloadObject(SDL_SharedObject *handle) {
    const char *vkptr = getenv("VULKAN_PTR");
    if (vkptr != NULL && vkptr[0] != '\0') {
        void *vulkanHandle = (void *) (uintptr_t) strtoull(vkptr, NULL, 16);
        if ((void *) handle == vulkanHandle) return;
    }
    if (realSdlUnloadObject) realSdlUnloadObject(handle);
}

void *sdlDlsymProxy(const char *symbol, void *real) {
    if (strcmp(symbol, "SDL_InitSubSystem") == 0) {
        if (realSdlInitSubSystem == NULL) realSdlInitSubSystem = (SDL_InitSubSystem_t) real;
        return (void *) proxy_SDL_InitSubSystem;
    }
    if (strcmp(symbol, "SDL_CreateWindow") == 0) {
        if (realSdlCreateWindow == NULL) realSdlCreateWindow = (SDL_CreateWindow_t) real;
        return (void *) proxy_SDL_CreateWindow;
    }
    if (strcmp(symbol, "SDL_CreateWindowWithProperties") == 0) {
        if (realSdlCreateWindowWithProperties == NULL) realSdlCreateWindowWithProperties = (SDL_CreateWindowWithProperties_t) real;
        return (void *) proxy_SDL_CreateWindowWithProperties;
    }
    if (strcmp(symbol, "SDL_DestroyWindow") == 0) {
        if (realSdlDestroyWindow == NULL) realSdlDestroyWindow = (SDL_DestroyWindow_t) real;
        return (void *) proxy_SDL_DestroyWindow;
    }
    if (strcmp(symbol, "SDL_GetWindowFromEvent") == 0) {
        if (realSdlGetWindowFromEvent == NULL) realSdlGetWindowFromEvent = (SDL_GetWindowFromEvent_t) real;
        return (void *) proxy_SDL_GetWindowFromEvent;
    }
    if (strcmp(symbol, "SDL_GetWindowFromID") == 0) {
        if (realSdlGetWindowFromID == NULL) realSdlGetWindowFromID = (SDL_GetWindowFromID_t) real;
        return (void *) proxy_SDL_GetWindowFromID;
    }
    if (strcmp(symbol, "SDL_LoadObject") == 0) {
        if (realSdlLoadObject == NULL) realSdlLoadObject = (SDL_LoadObject_t) real;
        return (void *) proxy_SDL_LoadObject;
    }
    if (strcmp(symbol, "SDL_LoadFunction") == 0) {
        if (realSdlLoadFunction == NULL) realSdlLoadFunction = (SDL_LoadFunction_t) real;
        return (void *) proxy_SDL_LoadFunction;
    }
    if (strcmp(symbol, "SDL_UnloadObject") == 0) {
        if (realSdlUnloadObject == NULL) realSdlUnloadObject = (SDL_UnloadObject_t) real;
        return (void *) proxy_SDL_UnloadObject;
    }
    if (strcmp(symbol, "SDL_GL_GetProcAddress") == 0) {
        return (void *) proxy_SDL_GL_GetProcAddress;
    }
    if (strcmp(symbol, "SDL_GL_LoadLibrary") == 0) {
        return (void *) proxy_SDL_GL_LoadLibrary;
    }
    if (strcmp(symbol, "SDL_GL_SetAttribute") == 0) {
        return (void *) proxy_SDL_GL_SetAttribute;
    }
    if (strcmp(symbol, "SDL_GL_GetAttribute") == 0) {
        return (void *) proxy_SDL_GL_GetAttribute;
    }
    if (strcmp(symbol, "SDL_GL_UnloadLibrary") == 0) {
        return (void *) proxy_SDL_GL_UnloadLibrary;
    }
    return NULL;
}

void create_sdl_hooks(bytehook_hook_all_t bytehook_hook_all_p) {
    bytehook_stub_t stub_SDL_InitSubSystem = bytehook_hook_all_p(NULL, "SDL_InitSubSystem", &custom_SDL_InitSubSystem_Func, NULL, NULL);
    bytehook_stub_t stub_SDL_OpenJoystick = bytehook_hook_all_p(NULL, "SDL_OpenJoystick", &custom_SDL_OpenJoystick_Func, NULL, NULL);
    bytehook_stub_t stub_SDL_OpenGamepad  = bytehook_hook_all_p(NULL, "SDL_OpenGamepad",  &custom_SDL_OpenGamepad_Func,  NULL, NULL);
    bytehook_stub_t stub_SDL_CloseJoystick = bytehook_hook_all_p(NULL, "SDL_CloseJoystick", &custom_SDL_CloseJoystick_Func, NULL, NULL);
    bytehook_stub_t stub_SDL_CloseGamepad  = bytehook_hook_all_p(NULL, "SDL_CloseGamepad",  &custom_SDL_CloseGamepad_Func,  NULL, NULL);
    bytehook_stub_t stub_SDL_GetWindowFromEvent = bytehook_hook_all_p(NULL, "SDL_GetWindowFromEvent", &custom_SDL_GetWindowFromEvent_Func, NULL, NULL);
    bytehook_stub_t stub_SDL_GetWindowFromID = bytehook_hook_all_p(NULL, "SDL_GetWindowFromID", &custom_SDL_GetWindowFromID_Func, NULL, NULL);
    bytehook_stub_t stub_SDL_CreateWindow = bytehook_hook_all_p(NULL, "SDL_CreateWindow", &custom_SDL_CreateWindow_Func, NULL, NULL);
    bytehook_stub_t stub_SDL_CreateWindowWithProperties = bytehook_hook_all_p(NULL, "SDL_CreateWindowWithProperties", &custom_SDL_CreateWindowWithProperties_Func, NULL, NULL);
    bytehook_stub_t stub_SDL_LoadFunction = bytehook_hook_all_p(NULL, "SDL_LoadFunction", &custom_SDL_LoadFunction_Func, NULL, NULL);
    bytehook_stub_t stub_SDL_EGLGetProcAddress = bytehook_hook_all_p(NULL, "SDL_EGL_GetProcAddress", &custom_SDL_EGLGetProcAddress_Func, NULL, NULL);
    bytehook_stub_t stub_SDL_LoadObject = bytehook_hook_all_p(NULL, "SDL_LoadObject", &custom_SDL_LoadObject_Func, NULL, NULL);
    bytehook_stub_t stub_SDL_UnloadObject = bytehook_hook_all_p(NULL, "SDL_UnloadObject", &custom_SDL_UnloadObject_Func, NULL, NULL);
    bytehook_stub_t stub_SDL_DestroyWindow = bytehook_hook_all_p(NULL, "SDL_DestroyWindow", &custom_SDL_DestroyWindow_Func, NULL, NULL);

    bytehook_stub_t stub_SDL_GL_GetProcAddress = bytehook_hook_all_p(NULL, "SDL_GL_GetProcAddress", &custom_SDL_GL_GetProcAddress_Func, NULL, NULL);
    bytehook_stub_t stub_SDL_GL_LoadLibrary = bytehook_hook_all_p(NULL, "SDL_GL_LoadLibrary", &custom_SDL_GL_LoadLibrary_Func, NULL, NULL);
    bytehook_stub_t stub_SDL_GL_SetAttribute = bytehook_hook_all_p(NULL, "SDL_GL_SetAttribute", &custom_SDL_GL_SetAttribute_Func, NULL, NULL);
    bytehook_stub_t stub_SDL_GL_GetAttribute = bytehook_hook_all_p(NULL, "SDL_GL_GetAttribute", &custom_SDL_GL_GetAttribute_Func, NULL, NULL);
    bytehook_stub_t stub_SDL_GL_UnloadLibrary = bytehook_hook_all_p(NULL, "SDL_GL_UnloadLibrary", &custom_SDL_GL_UnloadLibrary_Func, NULL, NULL);

    LOGI("Successfully initialized SDL hooks, stub: %p\n", stub_SDL_InitSubSystem);
}
