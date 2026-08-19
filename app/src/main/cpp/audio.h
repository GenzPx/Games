#pragma once

bool audio_init();
void audio_shutdown();
void audio_music(const char* path, float vol);
void audio_stop_music();
void audio_sfx(const char* path, float vol);
void audio_set_music_vol(float v);
void audio_set_sfx_vol(float v);
