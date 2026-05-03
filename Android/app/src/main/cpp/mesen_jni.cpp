#include <jni.h>
#include <android/log.h>
#include <GLES3/gl3.h>

#include <memory>
#include <string>
#include <cstring>
#include <mutex>
#include <vector>

// Mesen2 core headers
#include "Core/Shared/Emulator.h"
#include "Core/Shared/EmuSettings.h"
#include "Core/Shared/KeyManager.h"
#include "Core/Shared/MessageManager.h"
#include "Core/Shared/Audio/SoundMixer.h"
#include "Core/Shared/Video/VideoDecoder.h"
#include "Core/Shared/Video/VideoRenderer.h"
#include "Utilities/FolderUtilities.h"
#include "Utilities/VirtualFile.h"

// Android-specific implementations
#include "android_renderer.h"
#include "android_audio.h"
#include "android_key_manager.h"

#define TAG "MesenJNI"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

// ── Global emulator state ─────────────────────────────────────────────────────
static std::unique_ptr<Emulator>           g_emu;
static std::unique_ptr<AndroidRenderer>    g_renderer;
static std::unique_ptr<AndroidAudioDevice> g_audio;
static std::unique_ptr<AndroidKeyManager>  g_keyManager;

// ── OpenGL state (created on the GL thread) ───────────────────────────────────
static GLuint g_program      = 0;
static GLuint g_vao          = 0;
static GLuint g_vbo          = 0;
static GLuint g_texture      = 0;
static int    g_uTexLoc      = -1;
static uint32_t g_texWidth   = 0;
static uint32_t g_texHeight  = 0;

static std::vector<uint32_t> g_pixelScratch; // scratch buffer for GetFrameIfReady

// ── Shader sources ────────────────────────────────────────────────────────────
static const char* kVertSrc = R"(#version 300 es
layout(location=0) in vec2 aPos;
layout(location=1) in vec2 aUV;
out vec2 vUV;
void main() {
    vUV = aUV;
    gl_Position = vec4(aPos, 0.0, 1.0);
}
)";

static const char* kFragSrc = R"(#version 300 es
precision mediump float;
in vec2 vUV;
uniform sampler2D uTex;
out vec4 fragColor;
void main() {
    fragColor = texture(uTex, vUV);
}
)";

// ── GL helpers ────────────────────────────────────────────────────────────────
static GLuint CompileShader(GLenum type, const char* src)
{
    GLuint s = glCreateShader(type);
    glShaderSource(s, 1, &src, nullptr);
    glCompileShader(s);
    GLint ok = 0;
    glGetShaderiv(s, GL_COMPILE_STATUS, &ok);
    if (!ok) {
        char buf[512];
        glGetShaderInfoLog(s, sizeof(buf), nullptr, buf);
        LOGE("Shader compile error: %s", buf);
    }
    return s;
}

static GLuint CreateProgram()
{
    GLuint vs = CompileShader(GL_VERTEX_SHADER,   kVertSrc);
    GLuint fs = CompileShader(GL_FRAGMENT_SHADER, kFragSrc);
    GLuint p  = glCreateProgram();
    glAttachShader(p, vs);
    glAttachShader(p, fs);
    glLinkProgram(p);
    glDeleteShader(vs);
    glDeleteShader(fs);
    GLint ok = 0;
    glGetProgramiv(p, GL_LINK_STATUS, &ok);
    if (!ok) {
        char buf[512];
        glGetProgramInfoLog(p, sizeof(buf), nullptr, buf);
        LOGE("Program link error: %s", buf);
    }
    return p;
}

// ── JNI functions ─────────────────────────────────────────────────────────────
extern "C" {

// Called once on startup from EmulatorActivity.onCreate()
JNIEXPORT void JNICALL
Java_com_mesen2_android_NativeLib_initialize(JNIEnv* env, jobject /*thiz*/,
                                              jstring homeFolder)
{
    if (g_emu) return; // already initialized

    const char* home = env->GetStringUTFChars(homeFolder, nullptr);
    FolderUtilities::SetHomeFolder(home);
    env->ReleaseStringUTFChars(homeFolder, home);

    g_emu = std::make_unique<Emulator>();
    g_emu->Initialize();

    // NES controller setup: port 1, key codes matching NesKeyCode namespace
    NesConfig& nes = g_emu->GetSettings()->GetNesConfig();
    nes.Port1.Type = ControllerType::NesController;
    auto& m = nes.Port1.Keys.Mapping1;
    m.A      = NesKeyCode::A;
    m.B      = NesKeyCode::B;
    m.Select = NesKeyCode::Select;
    m.Start  = NesKeyCode::Start;
    m.Up     = NesKeyCode::Up;
    m.Down   = NesKeyCode::Down;
    m.Left   = NesKeyCode::Left;
    m.Right  = NesKeyCode::Right;

    // HD packs enabled by default
    nes.EnableHdPacks = true;

    // Video filter: xBRZ 2x as default upscaler (can be changed via settings)
    VideoConfig& vid = g_emu->GetSettings()->GetVideoConfig();
    vid.VideoFilter = VideoFilterType::None; // start with no filter; user can enable

    g_audio      = std::make_unique<AndroidAudioDevice>();
    g_keyManager = std::make_unique<AndroidKeyManager>();

    KeyManager::SetSettings(g_emu->GetSettings());
    KeyManager::RegisterKeyManager(g_keyManager.get());

    g_emu->GetSoundMixer()->RegisterAudioDevice(g_audio.get());

    g_renderer = std::make_unique<AndroidRenderer>(g_emu.get());

    LOGI("Emulator initialized. Home: %s", FolderUtilities::GetHomeFolder().c_str());
}

JNIEXPORT void JNICALL
Java_com_mesen2_android_NativeLib_release(JNIEnv* /*env*/, jobject /*thiz*/)
{
    if (!g_emu) return;
    g_renderer.reset();
    g_emu->Stop(true);
    g_emu->Release();
    g_audio.reset();
    g_keyManager.reset();
    g_emu.reset();
}

JNIEXPORT jboolean JNICALL
Java_com_mesen2_android_NativeLib_loadRom(JNIEnv* env, jobject /*thiz*/, jstring romPath)
{
    if (!g_emu) return JNI_FALSE;
    const char* path = env->GetStringUTFChars(romPath, nullptr);
    bool ok = g_emu->LoadRom((VirtualFile)path, VirtualFile());
    env->ReleaseStringUTFChars(romPath, path);
    LOGI("LoadRom(%s) -> %s", path, ok ? "OK" : "FAILED");
    return ok ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_com_mesen2_android_NativeLib_stopRom(JNIEnv* /*env*/, jobject /*thiz*/)
{
    if (g_emu) g_emu->Stop(false);
}

JNIEXPORT void JNICALL
Java_com_mesen2_android_NativeLib_pause(JNIEnv* /*env*/, jobject /*thiz*/)
{
    if (g_emu) g_emu->Pause();
}

JNIEXPORT void JNICALL
Java_com_mesen2_android_NativeLib_resume(JNIEnv* /*env*/, jobject /*thiz*/)
{
    if (g_emu) g_emu->Resume();
}

JNIEXPORT jboolean JNICALL
Java_com_mesen2_android_NativeLib_isRunning(JNIEnv* /*env*/, jobject /*thiz*/)
{
    return (g_emu && g_emu->IsRunning()) ? JNI_TRUE : JNI_FALSE;
}

// Button press/release – buttonId matches NesKeyCode values
JNIEXPORT void JNICALL
Java_com_mesen2_android_NativeLib_setButtonState(JNIEnv* /*env*/, jobject /*thiz*/,
                                                  jint buttonId, jboolean pressed)
{
    if (g_keyManager)
        g_keyManager->SetKeyState((uint16_t)buttonId, pressed == JNI_TRUE);
}

// Video filter selection (maps to VideoFilterType enum)
JNIEXPORT void JNICALL
Java_com_mesen2_android_NativeLib_setVideoFilter(JNIEnv* /*env*/, jobject /*thiz*/,
                                                  jint filterType)
{
    if (!g_emu) return;
    VideoConfig& vid = g_emu->GetSettings()->GetVideoConfig();
    vid.VideoFilter = (VideoFilterType)filterType;
}

JNIEXPORT void JNICALL
Java_com_mesen2_android_NativeLib_setHdPacksEnabled(JNIEnv* /*env*/, jobject /*thiz*/,
                                                     jboolean enabled)
{
    if (!g_emu) return;
    NesConfig& nes = g_emu->GetSettings()->GetNesConfig();
    nes.EnableHdPacks = (enabled == JNI_TRUE);
}

// ── OpenGL init (called on GL thread) ─────────────────────────────────────────
JNIEXPORT void JNICALL
Java_com_mesen2_android_NativeLib_glInit(JNIEnv* /*env*/, jobject /*thiz*/)
{
    // Full-screen quad (NDC)
    static const float kVerts[] = {
        // pos        uv
        -1.f, -1.f,  0.f, 1.f,
         1.f, -1.f,  1.f, 1.f,
        -1.f,  1.f,  0.f, 0.f,
         1.f,  1.f,  1.f, 0.f,
    };

    g_program = CreateProgram();
    g_uTexLoc = glGetUniformLocation(g_program, "uTex");

    glGenVertexArrays(1, &g_vao);
    glBindVertexArray(g_vao);

    glGenBuffers(1, &g_vbo);
    glBindBuffer(GL_ARRAY_BUFFER, g_vbo);
    glBufferData(GL_ARRAY_BUFFER, sizeof(kVerts), kVerts, GL_STATIC_DRAW);

    glEnableVertexAttribArray(0);
    glVertexAttribPointer(0, 2, GL_FLOAT, GL_FALSE, 4 * sizeof(float), (void*)0);
    glEnableVertexAttribArray(1);
    glVertexAttribPointer(1, 2, GL_FLOAT, GL_FALSE, 4 * sizeof(float),
                          (void*)(2 * sizeof(float)));

    glBindVertexArray(0);

    glGenTextures(1, &g_texture);
    glBindTexture(GL_TEXTURE_2D, g_texture);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);

    // Allocate initial texture (256x240 NES native)
    g_texWidth  = 256;
    g_texHeight = 240;
    g_pixelScratch.resize(g_texWidth * g_texHeight, 0);
    glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA, (GLsizei)g_texWidth, (GLsizei)g_texHeight,
                 0, GL_RGBA, GL_UNSIGNED_BYTE, g_pixelScratch.data());

    glBindTexture(GL_TEXTURE_2D, 0);
    LOGI("GL initialized");
}

// ── OpenGL draw (called on GL thread each vsync) ──────────────────────────────
JNIEXPORT void JNICALL
Java_com_mesen2_android_NativeLib_glDrawFrame(JNIEnv* /*env*/, jobject /*thiz*/,
                                               jint viewW, jint viewH)
{
    glViewport(0, 0, viewW, viewH);
    glClearColor(0.f, 0.f, 0.f, 1.f);
    glClear(GL_COLOR_BUFFER_BIT);

    if (!g_renderer || !g_program) return;

    uint32_t fw = g_renderer->GetWidth();
    uint32_t fh = g_renderer->GetHeight();
    if (fw == 0 || fh == 0) return;

    // Ensure scratch buffer is large enough
    if (g_pixelScratch.size() < (size_t)fw * fh)
        g_pixelScratch.resize((size_t)fw * fh);

    glBindTexture(GL_TEXTURE_2D, g_texture);

    uint32_t outW = 0, outH = 0;
    if (g_renderer->GetFrameIfReady(g_pixelScratch.data(), outW, outH)) {
        if (outW != g_texWidth || outH != g_texHeight) {
            g_texWidth  = outW;
            g_texHeight = outH;
            glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA,
                         (GLsizei)g_texWidth, (GLsizei)g_texHeight,
                         0, GL_RGBA, GL_UNSIGNED_BYTE, g_pixelScratch.data());
        } else {
            glTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0,
                            (GLsizei)g_texWidth, (GLsizei)g_texHeight,
                            GL_RGBA, GL_UNSIGNED_BYTE, g_pixelScratch.data());
        }
    }

    // Letterbox / aspect-ratio correction
    float nesAR  = (float)g_texWidth / (float)g_texHeight;
    float viewAR = (float)viewW / (float)viewH;
    float scaleX = 1.f, scaleY = 1.f;
    if (viewAR > nesAR) scaleX = nesAR / viewAR;
    else                scaleY = viewAR / nesAR;

    // Update quad scale via a simple uniform-free approach: just draw full-screen
    // and rely on viewport for aspect ratio correction (GPU-side letterbox).
    // If a more precise letterbox is needed, a matrix uniform can be added.

    glUseProgram(g_program);
    glUniform1i(g_uTexLoc, 0);
    glActiveTexture(GL_TEXTURE0);
    glBindVertexArray(g_vao);
    glDrawArrays(GL_TRIANGLE_STRIP, 0, 4);
    glBindVertexArray(0);
    glBindTexture(GL_TEXTURE_2D, 0);
    glUseProgram(0);
}

JNIEXPORT void JNICALL
Java_com_mesen2_android_NativeLib_glDestroy(JNIEnv* /*env*/, jobject /*thiz*/)
{
    if (g_vao)     { glDeleteVertexArrays(1, &g_vao);  g_vao = 0; }
    if (g_vbo)     { glDeleteBuffers(1, &g_vbo);       g_vbo = 0; }
    if (g_texture) { glDeleteTextures(1, &g_texture);  g_texture = 0; }
    if (g_program) { glDeleteProgram(g_program);       g_program = 0; }
}

JNIEXPORT jstring JNICALL
Java_com_mesen2_android_NativeLib_getLog(JNIEnv* env, jobject /*thiz*/)
{
    std::string log = MessageManager::GetLog();
    return env->NewStringUTF(log.c_str());
}

} // extern "C"
