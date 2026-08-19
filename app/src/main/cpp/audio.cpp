#include "audio.h"
#include <SDL.h>
#include <string.h>
#include <stdlib.h>

#define MINIAUDIO_IMPLEMENTATION
#define MA_NO_RUNTIME_LINKING
#define MA_NO_AAUDIO
#include "miniaudio.h"

struct SdlVfs {
    ma_vfs_callbacks cb;
};

static SdlVfs g_vfs;
static ma_engine g_eng;
static ma_sound g_music;
static int g_music_on = 0;
static char g_cur[256];
static float g_mvol = 0.42f;
static float g_svol = 0.75f;
static int g_ok = 0;

static ma_result vfs_open(ma_vfs*, const char* path, ma_uint32, ma_vfs_file* out) {
    SDL_RWops* rw = SDL_RWFromFile(path, "rb");
    if (!rw) return MA_DOES_NOT_EXIST;
    *out = (ma_vfs_file)rw;
    return MA_SUCCESS;
}
static ma_result vfs_close(ma_vfs*, ma_vfs_file file) {
    if (file) SDL_RWclose((SDL_RWops*)file);
    return MA_SUCCESS;
}
static ma_result vfs_read(ma_vfs*, ma_vfs_file file, void* dst, size_t sz, size_t* nread) {
    size_t n = SDL_RWread((SDL_RWops*)file, dst, 1, sz);
    if (nread) *nread = n;
    return MA_SUCCESS;
}
static ma_result vfs_seek(ma_vfs*, ma_vfs_file file, ma_int64 off, ma_seek_origin origin) {
    int whence = RW_SEEK_SET;
    if (origin == ma_seek_origin_current) whence = RW_SEEK_CUR;
    if (origin == ma_seek_origin_end) whence = RW_SEEK_END;
    if (SDL_RWseek((SDL_RWops*)file, off, whence) < 0) return MA_ERROR;
    return MA_SUCCESS;
}
static ma_result vfs_tell(ma_vfs*, ma_vfs_file file, ma_int64* pCursor) {
    Sint64 t = SDL_RWtell((SDL_RWops*)file);
    if (t < 0) return MA_ERROR;
    *pCursor = t;
    return MA_SUCCESS;
}
static ma_result vfs_info(ma_vfs*, ma_vfs_file file, ma_file_info* info) {
    Sint64 cur = SDL_RWtell((SDL_RWops*)file);
    Sint64 end = SDL_RWseek((SDL_RWops*)file, 0, RW_SEEK_END);
    SDL_RWseek((SDL_RWops*)file, cur, RW_SEEK_SET);
    if (end < 0) return MA_ERROR;
    info->sizeInBytes = (ma_uint64)end;
    return MA_SUCCESS;
}

bool audio_init() {
    memset(&g_vfs, 0, sizeof(g_vfs));
    g_vfs.cb.onOpen = vfs_open;
    g_vfs.cb.onClose = vfs_close;
    g_vfs.cb.onRead = vfs_read;
    g_vfs.cb.onSeek = vfs_seek;
    g_vfs.cb.onTell = vfs_tell;
    g_vfs.cb.onInfo = vfs_info;
    ma_engine_config cfg = ma_engine_config_init();
    cfg.pResourceManagerVFS = &g_vfs;
    if (ma_engine_init(&cfg, &g_eng) != MA_SUCCESS) return false;
    g_ok = 1;
    g_cur[0] = 0;
    return true;
}

void audio_shutdown() {
    if (!g_ok) return;
    audio_stop_music();
    ma_engine_uninit(&g_eng);
    g_ok = 0;
}

void audio_stop_music() {
    if (!g_ok) return;
    if (g_music_on) {
        ma_sound_uninit(&g_music);
        g_music_on = 0;
    }
    g_cur[0] = 0;
}

void audio_music(const char* path, float vol) {
    if (!g_ok || !path) return;
    if (strcmp(g_cur, path) == 0) {
        ma_sound_set_volume(&g_music, vol * g_mvol);
        return;
    }
    audio_stop_music();
    ma_uint32 flags = MA_SOUND_FLAG_STREAM | MA_SOUND_FLAG_LOOPING;
    if (ma_sound_init_from_file(&g_eng, path, flags, NULL, NULL, &g_music) != MA_SUCCESS) return;
    ma_sound_set_volume(&g_music, vol * g_mvol);
    ma_sound_start(&g_music);
    g_music_on = 1;
    strncpy(g_cur, path, sizeof(g_cur) - 1);
}

void audio_sfx(const char* path, float vol) {
    if (!g_ok || !path) return;
    ma_engine_play_sound(&g_eng, path, NULL);
    (void)vol;
}

void audio_set_music_vol(float v) {
    g_mvol = v;
    if (g_music_on) ma_sound_set_volume(&g_music, 0.42f * g_mvol);
}

void audio_set_sfx_vol(float v) { g_svol = v; }
