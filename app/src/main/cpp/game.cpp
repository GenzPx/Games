#include <SDL.h>
#include <math.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <algorithm>
#include <string>
#include <vector>
#include <map>
#include "audio.h"

#define STB_IMAGE_IMPLEMENTATION
#define STBI_NO_STDIO
#include "stb_image.h"
#define STB_TRUETYPE_IMPLEMENTATION
#include "stb_truetype.h"

static const int MAP = 48;
static const int TS = 16;
static const float DAY = 80.f;
static const float NIGHT = 55.f;

enum Tile {
    T_GRASS=0, T_DIRT, T_PATH, T_WATER,
    T_PINE, T_OAK, T_DEAD, T_FRUIT, T_STUMP,
    T_BUSH, T_BUSH0, T_ROCK, T_FLOWER
};
enum State { ST_SPLASH, ST_LOAD, ST_MENU, ST_SETTINGS, ST_SLOTS, ST_PLAY, ST_PAUSE, ST_DIALOG, ST_DEAD };

struct Tex { SDL_Texture* t=nullptr; int w=0,h=0; };
struct Pop { float x,y,t; char text[24]; };
struct Bit { float x,y,vx,vy,t; Uint32 col; };
struct Mob { float x,y,hp; };
struct Line { std::string speaker, port, text; };

static SDL_Window* g_win = nullptr;
static SDL_Renderer* g_ren = nullptr;
static int W=1280, H=720;
static State state = ST_SPLASH;
static float stateT = 0;
static int loadStep = 0;
static char loadMsg[64] = "Memuat...";

static Tex town, dung, logo, title, faceP, faceI, faceA;
static Tex tPine, tOak, tDead, tFruit, npcIbu;
static Tex knD, knL, knR, knU;
static Tex gtAtk, gtDash, gtSkill, gtStick, gtKnob;
static Tex uiBar;

static stbtt_fontinfo g_font;
static unsigned char* g_fontBuf = nullptr;
static std::map<std::string, Tex> textCache;

static int tiles[MAP][MAP];
static float px=24.2f, py=25.4f, sx=0, sy=0;
static int dir=1;
static float walk=0, hunger=1, warmth=1, hp=1, stamina=1;
static int wood=3, food=2, meat=0, day=1, quest=0;
static float clockT=10, fire=0.8f, fireX=24, fireY=23.6f;
static int lit=1;
static float shake=0, toastT=0, dashT=0, skillCd=0, iframe=0, atkT=0;
static char toast[96]="Jaga api sebelum malam.";
static int paused=0, dashing=0, atkDown=0;
static int stickId=-1, modeFromMenu=0;
static float lastStep=0;
static char lastTheme[128]="";

static std::vector<Pop> pops;
static std::vector<Bit> bits;
static std::vector<Mob> wolves;
static std::vector<Line> dialog;
static int dlgI=0;
static float dlgType=0;
static int slotSel=0;
static float bgmVol=1, sfxVol=1;
static Uint64 rngState = 11;

static Uint32 urand() { rngState = rngState*6364136223846793005ull + 1; return (Uint32)(rngState>>33); }
static float frand() { return (urand() & 0xFFFFFF) / 16777216.f; }

static int solid(int tx, int ty) {
    if (tx<0||ty<0||tx>=MAP||ty>=MAP) return 1;
    int t = tiles[ty][tx];
    return t==T_WATER||t==T_PINE||t==T_OAK||t==T_DEAD||t==T_FRUIT||t==T_ROCK;
}

static void say(const char* s) { strncpy(toast,s,sizeof(toast)-1); toastT=3.4f; }
static void pop(float x, float y, const char* s) {
    Pop p{}; p.x=x; p.y=y; p.t=1.1f; strncpy(p.text,s,23); pops.push_back(p);
}

static unsigned char* readAll(const char* path, int* outSz) {
    SDL_RWops* rw = SDL_RWFromFile(path, "rb");
    if (!rw) return nullptr;
    int sz = (int)SDL_RWsize(rw);
    auto* b = (unsigned char*)malloc(sz);
    SDL_RWread(rw, b, 1, sz);
    SDL_RWclose(rw);
    if (outSz) *outSz = sz;
    return b;
}

static Tex loadTex(const char* path) {
    Tex o{};
    int sz=0; unsigned char* buf = readAll(path, &sz);
    if (!buf) { SDL_Log("missing %s", path); return o; }
    int n=0; unsigned char* px = stbi_load_from_memory(buf, sz, &o.w, &o.h, &n, 4);
    free(buf);
    if (!px) return o;
    SDL_Surface* s = SDL_CreateRGBSurfaceWithFormatFrom(px, o.w, o.h, 32, o.w*4, SDL_PIXELFORMAT_RGBA32);
    o.t = SDL_CreateTextureFromSurface(g_ren, s);
    SDL_FreeSurface(s);
    stbi_image_free(px);
    if (o.t) SDL_SetTextureBlendMode(o.t, SDL_BLENDMODE_BLEND);
    return o;
}

static void blit(const Tex& t, float x, float y, float w=-1, float h=-1, SDL_Rect* src=nullptr) {
    if (!t.t) return;
    SDL_FRect d{x,y, w<0?(float)t.w:w, h<0?(float)t.h:h};
    SDL_RenderCopyF(g_ren, t.t, src, &d);
}

static void rect(float x,float y,float w,float h, Uint32 c, int fill=1) {
    SDL_SetRenderDrawBlendMode(g_ren, SDL_BLENDMODE_BLEND);
    SDL_SetRenderDrawColor(g_ren, (c>>16)&255, (c>>8)&255, c&255, (c>>24)&255);
    SDL_FRect r{x,y,w,h};
    if (fill) SDL_RenderFillRectF(g_ren, &r); else SDL_RenderDrawRectF(g_ren, &r);
}

static void rrect(float x,float y,float w,float h, Uint32 c) { rect(x,y,w,h,c,1); }

static Tex makeText(const char* s, float pxSize, Uint32 col) {
    std::string key = std::string(s) + "#" + std::to_string((int)pxSize) + "#" + std::to_string(col);
    auto it = textCache.find(key);
    if (it != textCache.end()) return it->second;
    Tex o{};
    if (!g_fontBuf || !s || !s[0]) return o;
    float scale = stbtt_ScaleForPixelHeight(&g_font, pxSize);
    int ascent; stbtt_GetFontVMetrics(&g_font, &ascent, nullptr, nullptr);
    int tw=8, th=(int)(pxSize*1.4f);
    // measure
    tw=4; const char* p=s;
    while (*p) {
        int g = stbtt_FindGlyphIndex(&g_font, (unsigned char)*p==0xC3 ? 0x20 : (unsigned char)*p);
        // utf8 decode
        unsigned int cp = (unsigned char)*p;
        int adv=1;
        if (cp>=0xC0) { if ((cp&0xE0)==0xC0){ cp=((cp&0x1F)<<6)|(((unsigned char)p[1])&0x3F); adv=2;}
            else if ((cp&0xF0)==0xE0){ cp=((cp&0x0F)<<12)|((((unsigned char)p[1])&0x3F)<<6)|(((unsigned char)p[2])&0x3F); adv=3;} }
        int ax, lsb; stbtt_GetCodepointHMetrics(&g_font, (int)cp, &ax, &lsb);
        tw += (int)(ax*scale);
        p += adv;
    }
    tw += 8; if (tw<8) tw=8;
    std::vector<unsigned char> bmp(tw*th, 0);
    float x=2; p=s;
    while (*p) {
        unsigned int cp=(unsigned char)*p; int adv=1;
        if (cp>=0xC0){ if((cp&0xE0)==0xC0){cp=((cp&0x1F)<<6)|(((unsigned char)p[1])&0x3F);adv=2;}
            else if((cp&0xF0)==0xE0){cp=((cp&0x0F)<<12)|((((unsigned char)p[1])&0x3F)<<6)|(((unsigned char)p[2])&0x3F);adv=3;} }
        int ax,lsb; stbtt_GetCodepointHMetrics(&g_font, (int)cp, &ax, &lsb);
        int x0,y0,x1,y1; stbtt_GetCodepointBitmapBox(&g_font,(int)cp,scale,scale,&x0,&y0,&x1,&y1);
        int dstx=(int)x+x0, dsty=(int)(ascent*scale)+y0;
        if (dstx>=0 && dsty>=0 && dstx<tw && dsty<th)
            stbtt_MakeCodepointBitmap(&g_font, &bmp[dsty*tw+dstx], x1-x0, y1-y0, tw, scale, scale, (int)cp);
        x += ax*scale;
        p += adv;
    }
    std::vector<unsigned char> rgba(tw*th*4);
    Uint8 R=(col>>16)&255,G=(col>>8)&255,B=col&255,A=(col>>24)&255; if(!A) A=255;
    for (int i=0;i<tw*th;i++){ rgba[i*4]=R; rgba[i*4+1]=G; rgba[i*4+2]=B; rgba[i*4+3]=(unsigned char)((bmp[i]*A)/255); }
    SDL_Surface* sf = SDL_CreateRGBSurfaceWithFormatFrom(rgba.data(), tw, th, 32, tw*4, SDL_PIXELFORMAT_RGBA32);
    o.t = SDL_CreateTextureFromSurface(g_ren, sf);
    SDL_FreeSurface(sf);
    o.w=tw; o.h=th;
    if (o.t) SDL_SetTextureBlendMode(o.t, SDL_BLENDMODE_BLEND);
    textCache[key]=o;
    return o;
}

static void drawText(const char* s, float x, float y, float pxSize, Uint32 col, int center=0) {
    Tex t = makeText(s, pxSize, col);
    if (!t.t) return;
    if (center) x -= t.w*0.5f;
    blit(t, x, y);
}

static void buildMap() {
    for (int j=0;j<MAP;j++) for (int i=0;i<MAP;i++) {
        int edge = i<2||j<2||i>MAP-3||j>MAP-3;
        float dx=i-12.f, dy=j-34.f;
        int pond = dx*dx+dy*dy < 20.f;
        tiles[j][i] = (edge||pond)? T_WATER : T_GRASS;
    }
    for (int j=20;j<=28;j++) for (int i=20;i<=28;i++) {
        float dx=i-24.f, dy=j-24.f, d=sqrtf(dx*dx+dy*dy);
        if (d<5.2f) tiles[j][i] = d<2.4f ? T_DIRT : T_PATH;
    }
    for (int i=24;i<=40;i++) tiles[24][i]=T_PATH;
    for (int j=24;j<=40;j++) tiles[j][36]=T_PATH;
    auto plant = [&](int kind, int n, float mind){
        int g=0;
        for (int k=0;k<n*6 && g<n;k++){
            int i=3+urand()%(MAP-6), j=3+urand()%(MAP-6);
            float dx=i-24.f, dy=j-24.f;
            if (dx*dx+dy*dy < mind*mind) continue;
            if (tiles[j][i]==T_GRASS){ tiles[j][i]=kind; g++; }
        }
    };
    plant(T_PINE, 28, 6);
    plant(T_OAK, 22, 6);
    plant(T_DEAD, 12, 5);
    plant(T_FRUIT, 10, 5);
    plant(T_BUSH, 26, 3);
    plant(T_ROCK, 16, 3);
    plant(T_FLOWER, 36, 2);
    tiles[22][22]=T_PATH;
    px=24.2f; py=25.4f; fireX=24; fireY=23.6f;
}

static const char* pref(const char* name) {
    static char path[512];
    char* base = SDL_GetPrefPath("HoshiDev", "LastSurvival");
    if (!base) return name;
    snprintf(path, sizeof(path), "%s%s", base, name);
    SDL_free(base);
    return path;
}

struct SaveBlob {
    Uint32 magic;
    int day, wood, food, meat, lit, quest;
    float x,y,hp,hunger,warmth,clock,fire,fx,fy;
    int tiles[MAP*MAP];
};

static int saveSlot(int slot) {
    SaveBlob b{};
    b.magic=0x4C535631; b.day=day; b.wood=wood; b.food=food; b.meat=meat; b.lit=lit; b.quest=quest;
    b.x=px; b.y=py; b.hp=hp; b.hunger=hunger; b.warmth=warmth; b.clock=clockT; b.fire=fire; b.fx=fireX; b.fy=fireY;
    memcpy(b.tiles, tiles, sizeof(tiles));
    char fn[32]; snprintf(fn,32,"slot%d.dat", slot);
    SDL_RWops* rw = SDL_RWFromFile(pref(fn), "wb");
    if (!rw) return 0;
    SDL_RWwrite(rw, &b, sizeof(b), 1);
    SDL_RWclose(rw);
    return 1;
}
static int loadSlot(int slot) {
    char fn[32]; snprintf(fn,32,"slot%d.dat", slot);
    SDL_RWops* rw = SDL_RWFromFile(pref(fn), "rb");
    if (!rw) return 0;
    SaveBlob b{};
    size_t n = SDL_RWread(rw, &b, sizeof(b), 1);
    SDL_RWclose(rw);
    if (!n || b.magic!=0x4C535631) return 0;
    day=b.day; wood=b.wood; food=b.food; meat=b.meat; lit=b.lit; quest=b.quest;
    px=b.x; py=b.y; hp=b.hp; hunger=b.hunger; warmth=b.warmth; clockT=b.clock; fire=b.fire; fireX=b.fx; fireY=b.fy;
    memcpy(tiles, b.tiles, sizeof(tiles));
    wolves.clear(); bits.clear(); pops.clear();
    return 1;
}
static int slotExists(int slot) {
    char fn[32]; snprintf(fn,32,"slot%d.dat", slot);
    SDL_RWops* rw = SDL_RWFromFile(pref(fn), "rb");
    if (!rw) return 0;
    SDL_RWclose(rw);
    return 1;
}

static void resetRun() {
    rngState = 11;
    buildMap();
    hunger=1; warmth=1; hp=1; stamina=1; wood=3; food=2; meat=0;
    day=1; clockT=10; fire=0.8f; lit=1; quest=0;
    wolves.clear(); bits.clear(); pops.clear();
    sx=sy=0; paused=0;
}

static void startDialog(const std::vector<Line>& lines) {
    dialog = lines; dlgI=0; dlgType=0; state=ST_DIALOG;
}

static void prolog() {
    startDialog({
        {"KNIGHT","player","Aku bangun di tanah yang salah musim. Pedangku patah."},
        {"KNIGHT","player","Langit terlalu oranye. Hutan ini... mendengar."},
        {"???","adik","Kakak. Jangan biarkan apinya padam."},
        {"IBU API","ibu","Hutan ingat nama orang yang membiarkan bara mati."},
        {"IBU API","ibu","Jaga api tujuh malam. Kalau padam, dia akan datang mencari."},
        {"KNIGHT","player","Siapa 'dia'?"},
        {"IBU API","ibu","Yang lapar. Tebang. Makan. Hidupkan api. Jangan sendirian di gelap."},
    });
    quest = 1;
}

static void interact() {
    audio_sfx("audio/sfx/click.ogg", 0.5f);
    int fx = (int)floorf(px + (dir==2?-0.85f: dir==3?0.85f:0));
    int fy = (int)floorf(py + (dir==0?-0.85f: dir==1?0.85f:0));
    if (fx<0||fy<0||fx>=MAP||fy>=MAP) return;
    int t = tiles[fy][fx];
    if (t==T_PINE||t==T_OAK||t==T_DEAD||t==T_FRUIT) {
        tiles[fy][fx] = T_STUMP;
        int add = (t==T_DEAD)?1: (t==T_OAK||t==T_PINE)?2:1;
        wood += add;
        if (t==T_FRUIT) { food++; pop(fx+0.5f, (float)fy, "+berry"); }
        shake=0.16f;
        audio_sfx((wood%2)? "audio/sfx/chop.ogg":"audio/sfx/chop2.ogg", 0.75f);
        pop(fx+0.5f, (float)fy, "+kayu");
        say("Kayu. Api butuh makan.");
    } else if (t==T_BUSH) {
        tiles[fy][fx]=T_BUSH0; food++; audio_sfx("audio/sfx/pickup.ogg",0.7f);
        pop(fx+0.5f,(float)fy,"+1 berry"); say("Berry. Makan sebelum lapar.");
    } else {
        float ddx=px-fireX, ddy=py-fireY, dd=sqrtf(ddx*ddx+ddy*ddy);
        if (wood>=3 && !lit) {
            fireX=px; fireY=py; lit=1; fire=0.9f; wood-=3;
            say("Api hidup lagi."); pop(px, py-0.4f, "API");
            audio_sfx("audio/sfx/start.ogg",0.7f);
        } else if (wood>=1 && lit && dd<2.3f) {
            wood--; fire=fminf(1.f, fire+0.34f);
            pop(fireX, fireY-0.5f, "fuel"); say("Kayu ke unggun.");
        } else say("Hadap pohon / semak. 3 kayu = nyalakan api.");
    }
    atkT = 0.18f;
    // melee wolves
    for (auto& m : wolves) {
        float dx=m.x-px, dy=m.y-py, d=sqrtf(dx*dx+dy*dy);
        if (d<1.35f) { m.hp-=0.45f; pop(m.x, m.y-0.4f, "HIT"); shake=0.2f; }
    }
}

static void eat() {
    if (food<=0) { say("Berry habis."); return; }
    food--; hunger=fminf(1.f, hunger+0.4f); hp=fminf(1.f, hp+0.12f);
    audio_sfx("audio/sfx/eat.ogg",0.8f); pop(px, py-0.5f, "+HP"); say("Kenyang.");
}

static void die(const char* why) {
    snprintf(toast, sizeof(toast), "%s  Hari ke-%d.", why, day);
    toastT=99; state=ST_DEAD; wolves.clear();
    audio_sfx("audio/sfx/dead.ogg",0.85f);
}

static void pumpMusic() {
    const char* track = "audio/music/pm_complex.mp3";
    if (state==ST_SPLASH || state==ST_LOAD) track = "audio/music/pm_intrepid.mp3";
    else if (state==ST_MENU || state==ST_SETTINGS || state==ST_SLOTS) track = "audio/music/pm_complex.mp3";
    else if (state==ST_DEAD) track = "audio/music/pm_anguish.mp3";
    else if (clockT >= DAY) track = (day%2)? "audio/music/pm_darkest.mp3" : "audio/music/night_b.ogg";
    else if (clockT > DAY-12) track = "audio/music/pm_gloom.mp3";
    else {
        float ddx=px-fireX, ddy=py-fireY;
        if (lit && ddx*ddx+ddy*ddy < 9.f) track = "audio/music/pm_longnote.mp3";
        else {
            static const char* days[] = {"audio/music/day_a.ogg","audio/music/day_b.ogg","audio/music/day_c.mp3","audio/music/pm_intrepid.mp3"};
            track = days[day & 3];
        }
    }
    if (strcmp(lastTheme, track)!=0) {
        strncpy(lastTheme, track, sizeof(lastTheme)-1);
        audio_music(track, 0.42f);
    }
}

static void tick(float dt) {
    float sp = dashing ? 5.4f : 3.15f;
    if (dashing) { warmth=fmaxf(0, warmth-dt*0.02f); stamina=fmaxf(0, stamina-dt*0.35f); }
    else stamina=fminf(1.f, stamina+dt*0.18f);
    if (dashT>0) dashT-=dt;
    if (iframe>0) iframe-=dt;
    if (atkT>0) atkT-=dt;
    if (skillCd>0) skillCd-=dt;
    if (hypotf(sx,sy)>0.08f) {
        dir = (fabsf(sx)>fabsf(sy)) ? (sx<0?2:3) : (sy<0?0:1);
        walk += dt*9.f;
        lastStep += dt;
        if (lastStep>0.32f) { lastStep=0; audio_sfx("audio/sfx/step.ogg", 0.28f); }
        float nx=px+sx*sp*dt, ny=py+sy*sp*dt;
        if (!solid((int)floorf(nx),(int)floorf(py))) px=fminf(MAP-1.3f, fmaxf(1.3f,nx));
        if (!solid((int)floorf(px),(int)floorf(ny))) py=fminf(MAP-1.3f, fmaxf(1.3f,ny));
    }
    clockT += dt;
    if (clockT >= DAY+NIGHT) {
        clockT -= DAY+NIGHT; day++;
        char buf[64]; snprintf(buf,64,"Hari %d. Kayu. Makanan. Api.", day); say(buf);
        for (int j=0;j<MAP;j++) for (int i=0;i<MAP;i++)
            if (tiles[j][i]==T_BUSH0 && frand()<0.6f) tiles[j][i]=T_BUSH;
        if (day==2 && quest<3) {
            startDialog({{"IBU API","ibu","Kamu masih hidup. Bagus. Ada menara asap di timur. Cari sebelum malam ketiga."}});
            quest=3;
        }
        saveSlot(0);
    }
    int night = clockT>=DAY;
    if (clockT>(DAY-9) && clockT<DAY && toastT<0.2f) say("Matahari terbenam. Balik ke api.");
    hunger = fmaxf(0, hunger-dt*0.011f);
    float ddx=px-fireX, ddy=py-fireY, near = lit && fire>0.04f && (ddx*ddx+ddy*ddy)<11.5f;
    if (night && !near) warmth -= dt*0.05f;
    else if (near) warmth = fminf(1.f, warmth+dt*0.14f);
    else warmth = fminf(1.f, warmth+dt*0.012f);
    if (lit) fire = fmaxf(0, fire-dt*0.016f);
    if (fire<=0) lit=0;
    if (night) {
        if ((int)wolves.size() < 1+day/2 && frand()<dt*0.22f)
            wolves.push_back({ frand()<0.5f?3.f:(float)MAP-3.f, 6.f+urand()%(MAP-12), 1.f });
        if (quest==1 && clockT>DAY+2) {
            startDialog({
                {"???","adik","Jangan lari dari api, kakak."},
                {"IBU API","ibu","Itu bukan angin. Tebas. Atau bakar."},
            });
            quest=2;
        }
    } else wolves.clear();

    for (size_t i=0;i<wolves.size();) {
        Mob& m = wolves[i];
        float fd=hypotf(m.x-fireX, m.y-fireY);
        if (lit && fire>0.05f && fd<4.8f) {
            m.x += (m.x-fireX)/(fd+0.2f)*2.4f*dt;
            m.y += (m.y-fireY)/(fd+0.2f)*2.4f*dt;
        } else {
            float d=hypotf(px-m.x, py-m.y)+0.05f;
            m.x += (px-m.x)/d*1.55f*dt;
            m.y += (py-m.y)/d*1.55f*dt;
            if (d<0.72f && iframe<=0) {
                warmth-=0.12f; hunger-=0.03f; hp-=0.12f; shake=0.35f; iframe=0.55f;
                say("Digigit!"); pop(px, py-0.5f, "HIT");
                m.x -= (px-m.x)/d*2.2f; m.y -= (py-m.y)/d*2.2f;
            }
        }
        if (m.hp<=0) { pop(m.x,m.y,"+daging"); meat++; wolves.erase(wolves.begin()+i); continue; }
        if (m.x<1.5f||m.y<1.5f||m.x>MAP-1.5f||m.y>MAP-1.5f) { wolves.erase(wolves.begin()+i); continue; }
        i++;
    }
    if (lit && fire>0 && frand()<dt*14.f)
        bits.push_back({fireX+frand()*0.4f-0.2f, fireY, frand()*0.4f-0.2f, -1.4f-frand(), 0.5f, 0xFFFFCD75});
    for (size_t i=0;i<bits.size();){ auto& b=bits[i]; b.t-=dt; b.x+=b.vx*dt; b.y+=b.vy*dt; b.vy-=0.4f*dt; if(b.t<=0) bits.erase(bits.begin()+i); else i++; }
    for (size_t i=0;i<pops.size();){ auto& p=pops[i]; p.t-=dt; p.y-=dt*0.6f; if(p.t<=0) pops.erase(pops.begin()+i); else i++; }
    if (shake>0) shake-=dt;
    if (toastT>0) toastT-=dt;
    if (hunger<=0) die("Kamu kelaparan");
    else if (warmth<=0) die("Kamu membeku");
    else if (hp<=0) die("Kamu roboh");
}

static int doLoadStep() {
    switch (loadStep) {
        case 0: town=loadTex("gfx/town.png"); strcpy(loadMsg,"Peta hutan"); break;
        case 1: dung=loadTex("gfx/dungeon.png"); strcpy(loadMsg,"Gua & bara"); break;
        case 2: logo=loadTex("ui/logo_hoshidev.png"); title=loadTex("ui/title_banner.png"); strcpy(loadMsg,"HoshiDev"); break;
        case 3: faceP=loadTex("ui/gt_portrait.png"); faceI=loadTex("ui/portrait_ibu.png"); faceA=loadTex("ui/portrait_adik.png"); strcpy(loadMsg,"Wajah"); break;
        case 4: tPine=loadTex("gfx/tree_pine.png"); tOak=loadTex("gfx/tree_oak.png"); tDead=loadTex("gfx/tree_dead.png"); tFruit=loadTex("gfx/tree_fruit.png"); strcpy(loadMsg,"Empat pohon"); break;
        case 5: knD=loadTex("gfx/knight_d.png"); knL=loadTex("gfx/knight_l.png"); knR=loadTex("gfx/knight_r.png"); knU=loadTex("gfx/knight_u.png"); npcIbu=loadTex("gfx/npc_ibu.png"); strcpy(loadMsg,"Knight"); break;
        case 6: gtAtk=loadTex("ui/gt_atk.png"); gtDash=loadTex("ui/gt_dash.png"); gtSkill=loadTex("ui/gt_skill.png"); strcpy(loadMsg,"ATK DASH SKILL"); break;
        case 7: gtStick=loadTex("ui/gt_stick.png"); gtKnob=loadTex("ui/gt_knob.png"); strcpy(loadMsg,"Analog"); break;
        case 8: {
            int sz=0; g_fontBuf=readAll("font/NotoSans-Bold.ttf",&sz);
            if (g_fontBuf) stbtt_InitFont(&g_font, g_fontBuf, 0);
            strcpy(loadMsg,"Huruf");
        } break;
        default: return 1;
    }
    loadStep++;
    return 0;
}

static SDL_Rect tileSrc(int c, int r, int tw=1, int th=1) {
    return SDL_Rect{c*16,r*16,tw*16,th*16};
}

static void drawWorld() {
    int scale = (int)fminf((float)W/(16*20), (float)H/(16*12));
    if (scale<3) scale=3; if (scale>7) scale=7;
    float tw = (float)(TS*scale);
    float camX = px*tw - W/2.f, camY = py*tw - H/2.f;
    if (shake>0) { camX += (frand()-0.5f)*10.f*shake; camY += (frand()-0.5f)*10.f*shake; }
    auto gx=[&](float tx){ return tx*tw-camX; };
    auto gy=[&](float ty){ return ty*tw-camY; };
    int x0=(int)(camX/tw)-1, y0=(int)(camY/tw)-1;
    int x1=x0+W/(int)tw+3, y1=y0+H/(int)tw+3;
    for (int ty=y0; ty<=y1; ty++) for (int tx=x0; tx<=x1; tx++) {
        if (tx<0||ty<0||tx>=MAP||ty>=MAP) continue;
        int t=tiles[ty][tx];
        SDL_Rect src = tileSrc(0,0);
        Tex* atlas=&town;
        if (t==T_DIRT) src=tileSrc(0,1);
        else if (t==T_PATH) src=tileSrc(6,3);
        else if (t==T_WATER) { atlas=&dung; src=tileSrc(0,3); }
        else src = ((tx+ty)&1)? tileSrc(1,0):tileSrc(0,0);
        blit(*atlas, gx((float)tx), gy((float)ty), tw, tw, &src);
        if (t==T_FLOWER) { SDL_Rect f=tileSrc(2,0); blit(town, gx((float)tx), gy((float)ty), tw, tw, &f); }
    }
    struct Dr { float z; int kind; int tx,ty; float fx,fy; };
    std::vector<Dr> dr;
    for (int ty=y0; ty<=y1; ty++) for (int tx=x0; tx<=x1; tx++) {
        if (tx<0||ty<0||tx>=MAP||ty>=MAP) continue;
        int t=tiles[ty][tx];
        if (t==T_PINE||t==T_OAK||t==T_DEAD||t==T_FRUIT||t==T_STUMP||t==T_BUSH||t==T_BUSH0||t==T_ROCK)
            dr.push_back({(float)ty+0.8f, t, tx, ty, 0,0});
    }
    dr.push_back({22.8f, 100, 0,0, 21.5f, 21.2f}); // tent
    if (lit && fire>0) dr.push_back({fireY+0.4f, 101, 0,0, fireX, fireY});
    dr.push_back({23.2f, 102, 0,0, 23.2f, 23.1f}); // ibu
    for (auto& m: wolves) dr.push_back({m.y+0.3f, 103, 0,0, m.x, m.y});
    dr.push_back({py+0.35f, 104, 0,0, px, py});
    std::sort(dr.begin(), dr.end(), [](const Dr&a,const Dr&b){return a.z<b.z;});
    for (auto& d: dr) {
        float x=gx(d.kind>=100? d.fx:(float)d.tx), y=gy(d.kind>=100? d.fy:(float)d.ty);
        if (d.kind==T_PINE) blit(tPine, x-tw*0.2f, y-tw*2.2f, tw*1.3f, tw*3.2f);
        else if (d.kind==T_OAK) blit(tOak, x-tw*0.3f, y-tw*1.8f, tw*1.6f, tw*2.6f);
        else if (d.kind==T_DEAD) blit(tDead, x-tw*0.25f, y-tw*1.8f, tw*1.5f, tw*2.6f);
        else if (d.kind==T_FRUIT) blit(tFruit, x-tw*0.3f, y-tw*1.8f, tw*1.6f, tw*2.6f);
        else if (d.kind==T_STUMP) { SDL_Rect s=tileSrc(4,2); blit(town, x, y, tw, tw, &s); }
        else if (d.kind==T_BUSH||d.kind==T_BUSH0) {
            SDL_Rect s=tileSrc(d.kind==T_BUSH?5:5, d.kind==T_BUSH?0:1);
            blit(town, x, y, tw, tw, &s);
            if (d.kind==T_BUSH) { SDL_Rect m=tileSrc(5,2); blit(town, x, y+scale*6.f, tw, tw, &m); }
        } else if (d.kind==T_ROCK) { SDL_Rect s=tileSrc(0,0); blit(dung, x, y, tw, tw, &s); }
        else if (d.kind==100) { SDL_Rect s=tileSrc(8,4,2,2); blit(town, x, y, tw*2, tw*2, &s); }
        else if (d.kind==101) {
            SDL_Rect b=tileSrc(5,2), f=tileSrc(5,1);
            blit(dung, x-tw/2, y-tw/4, tw, tw, &b);
            blit(dung, x-tw/2, y-tw*0.95f, tw, tw, &f);
        } else if (d.kind==102) blit(npcIbu, x-tw*0.4f, y-tw*0.9f, tw*1.1f, tw*1.5f);
        else if (d.kind==103) { SDL_Rect s=tileSrc(2,9); blit(dung, x-tw/2, y-tw/2, tw, tw, &s); }
        else if (d.kind==104) {
            float bob = hypotf(sx,sy)>0.1f ? sinf(walk*2.2f)*scale*0.35f : 0;
            Tex* ch = &knD; if (dir==0) ch=&knU; else if (dir==2) ch=&knL; else if (dir==3) ch=&knR;
            float cw=tw*1.1f, chh=tw*1.5f;
            blit(*ch, W/2.f-cw/2, H/2.f-chh*0.72f+bob, cw, chh);
            float bw=tw*0.88f, bh=6, bx=W/2.f-bw/2, by=H/2.f-chh*0.72f+bob+chh+2;
            rrect(bx,by,bw,bh, 0xCC10141C);
            rrect(bx+1,by+1,(bw-2)*hp,bh-2, hp>0.35f?0xFF3DDC6A:0xFFE84D4D);
        }
    }
    for (auto& b: bits) rect(gx(b.x), gy(b.y), (float)scale, (float)scale, b.col);
    for (auto& p: pops) drawText(p.text, gx(p.x), gy(p.y), 13, 0xFFFFF3C4, 1);

    float dusk=0;
    if (clockT>=DAY-10 && clockT<DAY) dusk=(clockT-(DAY-10))/10.f;
    else if (clockT>=DAY && clockT<=DAY+NIGHT-8) dusk=1;
    else if (clockT>DAY+NIGHT-8) dusk=fmaxf(0, 1.f-(clockT-(DAY+NIGHT-8))/8.f);
    if (dusk>0.02f) {
        SDL_SetRenderDrawBlendMode(g_ren, SDL_BLENDMODE_BLEND);
        SDL_SetRenderDrawColor(g_ren, 6,8,20, (Uint8)(180*dusk));
        SDL_FRect full{0,0,(float)W,(float)H};
        SDL_RenderFillRectF(g_ren, &full);
    }
}

static int hit(float x,float y,float cx,float cy,float r){ float dx=x-cx,dy=y-cy; return dx*dx+dy*dy<r*r; }

static void drawPad() {
    blit(gtStick, 104-62, H-112-62, 124, 124);
    blit(gtKnob, 104-25+sx*38, H-112-25+sy*38, 50, 50);
    float asz = atkDown?108.f:118.f;
    blit(gtAtk, W-96-asz/2, H-112-asz/2, asz, asz);
    float dsz = dashing?70.f:78.f;
    blit(gtDash, W-196-dsz/2, H-80-dsz/2, dsz, dsz);
    if (food<=0 && state==ST_PLAY) SDL_SetTextureAlphaMod(gtSkill.t, 120);
    blit(gtSkill, W-176-37, H-176-37, 74, 74);
    if (gtSkill.t) SDL_SetTextureAlphaMod(gtSkill.t, 255);
    if (skillCd>0) {
        char b[16]; snprintf(b,16,"%.0f", skillCd); drawText(b, W-176, H-176-8, 12, 0xFFFFD54A, 1);
    }
}

static void drawHud() {
    rrect(8,8,270,76, 0xB20C1018);
    blit(faceP, 12, 12, 64, 64);
    drawText("KNIGHT", 86, 14, 15, 0xFFFFFFFF);
    rrect(86,36,180,14, 0xFF1A1E28);
    rrect(87,37,178*hp,12, hp>0.35f?0xFF3DDC6A:0xFFE84D4D);
    rrect(86,54,180,12, 0xFF1A1E28);
    rrect(87,55,178*warmth,10, 0xFF4FC3F7);
    rrect(8,90,88,26, 0xB20C1018);
    rrect(104,90,88,26, 0xB20C1018);
    char b1[24], b2[24]; snprintf(b1,24,"WOOD %d",wood); snprintf(b2,24,"BERRY %d",food);
    drawText(b1, 52, 94, 12, 0xFFFFE082, 1);
    drawText(b2, 148, 94, 12, 0xFFFF8A80, 1);
    rrect(W-176.f,10,114,40, 0xB20C1018);
    int night=clockT>=DAY;
    char db[24]; snprintf(db,24, night?"NIGHT %d":"DAY %d", day);
    drawText(db, W-119.f, 14, 12, 0xFFFFFFFF, 1);
    float left = night? (DAY+NIGHT-clockT):(DAY-clockT);
    char tb[16]; snprintf(tb,16,"%ds",(int)left);
    drawText(tb, W-119.f, 30, 10, 0xFFFFD54A, 1);
    rrect(W-54.f,10,40,40, 0xCC0C1018);
    rrect(W-41.f,18,5,24, 0xFFFFFFFF);
    rrect(W-32.f,18,5,24, 0xFFFFFFFF);
    if (lit) {
        rrect(W/2.f-72,10,144,26, 0xB20C1018);
        rrect(W/2.f-64,16,128*fire,12, 0xFFFF8A3D);
        drawText("FIRE", W/2.f, 16, 10, 0xFFFFFFFF, 1);
    }
    if (toastT>0) {
        rrect(W/2.f-210,122,420,32, 0xCC10141C);
        drawText(toast, W/2.f, 128, 14, 0xFFFFFFFF, 1);
    }
}

static void drawBtn(float x,float y,float w,float h, const char* label, int gold) {
    rrect(x,y,w,h, gold?0xFFFFD54A:0xFFE8E8E8);
    drawText(label, x+w/2, y+h/2-10, 16, 0xFF2A2418, 1);
}

static int inBtn(float mx,float my,float x,float y,float w,float h){ return mx>=x&&my>=y&&mx<=x+w&&my<=y+h; }

static void render() {
    SDL_GetWindowSize(g_win, &W, &H);
    SDL_SetRenderDrawColor(g_ren, 12, 16, 24, 255);
    SDL_RenderClear(g_ren);

    if (state==ST_SPLASH) {
        SDL_SetRenderDrawColor(g_ren, 5,7,12,255); SDL_RenderClear(g_ren);
        blit(logo, W/2.f-160, H/2.f-80, 320, 160);
        drawText("HOSHIDEV", W/2.f, H*0.72f, 18, 0xFFFFE566, 1);
    } else if (state==ST_LOAD) {
        SDL_SetRenderDrawColor(g_ren, 8,10,16,255); SDL_RenderClear(g_ren);
        drawText("LAST SURVIVAL", W/2.f, H*0.32f, 28, 0xFFFFE566, 1);
        rrect(W/2.f-180, H*0.55f, 360, 16, 0xFF1A1E28);
        rrect(W/2.f-180, H*0.55f, 360.f*(loadStep/9.f), 16, 0xFFFFD54A);
        drawText(loadMsg, W/2.f, H*0.55f+28, 14, 0xFFD0D4DC, 1);
    } else if (state==ST_MENU) {
        drawWorld();
        rrect(0,0,(float)W,(float)H, 0x66051018);
        blit(title, W/2.f-220, H*0.12f, 440, 110);
        drawText("HOSHIDEV  /  GENZPX", W/2.f, H*0.30f, 14, 0xCCFFFFFF, 1);
        drawBtn(W/2.f-130, H*0.42f, 260, 48, "PLAY", 1);
        drawBtn(W/2.f-130, H*0.52f, 260, 48, "LOAD", 0);
        drawBtn(W/2.f-130, H*0.62f, 260, 48, "SETTINGS", 0);
        drawPad();
    } else if (state==ST_SETTINGS) {
        rrect(0,0,(float)W,(float)H, 0xE0051018);
        drawText("SETTINGS", W/2.f, H*0.18f, 28, 0xFFFFFFFF, 1);
        char b[32]; snprintf(b,32,"BGM  %.0f%%", bgmVol*100); drawText(b, W/2.f, H*0.36f, 18, 0xFFFFE082, 1);
        snprintf(b,32,"SFX  %.0f%%", sfxVol*100); drawText(b, W/2.f, H*0.46f, 18, 0xFFFF8A80, 1);
        drawBtn(W/2.f-200, H*0.36f, 50, 40, "-", 0); drawBtn(W/2.f+150, H*0.36f, 50, 40, "+", 1);
        drawBtn(W/2.f-200, H*0.46f, 50, 40, "-", 0); drawBtn(W/2.f+150, H*0.46f, 50, 40, "+", 1);
        drawText("Last Survival — HoshiDev / GenzPX", W/2.f, H*0.62f, 14, 0xFFD0D4DC, 1);
        drawText("Musik: Peter Moore (incompetech) CC-BY", W/2.f, H*0.68f, 12, 0xFF9AA0A8, 1);
        drawBtn(W/2.f-100, H*0.78f, 200, 44, "BACK", 1);
    } else if (state==ST_SLOTS) {
        rrect(0,0,(float)W,(float)H, 0xE0051018);
        drawText("LOAD", W/2.f, H*0.16f, 28, 0xFFFFFFFF, 1);
        for (int i=0;i<3;i++) {
            int ex=slotExists(i);
            char lab[40]; snprintf(lab,40, ex? "SLOT %d  —  ada save":"SLOT %d  —  kosong", i+1);
            drawBtn(W/2.f-180, H*0.32f+i*70, 360, 52, lab, ex);
        }
        drawBtn(W/2.f-100, H*0.80f, 200, 44, "BACK", 0);
    } else if (state==ST_PLAY || state==ST_PAUSE || state==ST_DIALOG || state==ST_DEAD) {
        drawWorld();
        drawHud();
        drawPad();
        if (state==ST_PAUSE) {
            rrect(0,0,(float)W,(float)H, 0xC0051018);
            drawText("PAUSED", W/2.f, H*0.32f, 28, 0xFFFFFFFF, 1);
            drawBtn(W/2.f-156, H*0.55f, 140, 46, "LOBBY", 0);
            drawBtn(W/2.f+16, H*0.55f, 140, 46, "RESUME", 1);
        }
        if (state==ST_DIALOG && dlgI<(int)dialog.size()) {
            rrect(40, H-168.f, W-80.f, 150, 0xEE0C1018);
            const Line& L = dialog[dlgI];
            Tex* f=&faceP; if (L.port=="ibu") f=&faceI; else if (L.port=="adik") f=&faceA;
            blit(*f, 52, H-156.f, 88, 88);
            drawText(L.speaker.c_str(), 156, H-156.f, 16, 0xFFFFD54A);
            int n = (int)fminf((float)L.text.size(), dlgType);
            std::string vis = L.text.substr(0, n);
            drawText(vis.c_str(), 156, H-128.f, 16, 0xFFFFFFFF);
            drawText("TAP", W-90.f, H-40.f, 12, 0xFF9AA0A8, 1);
        }
        if (state==ST_DEAD) {
            rrect(0,0,(float)W,(float)H, 0xD0051018);
            blit(faceP, W/2.f-48, H*0.18f, 96, 96);
            drawText("GAME OVER", W/2.f, H*0.42f, 28, 0xFFFFFFFF, 1);
            drawText(toast, W/2.f, H*0.50f, 15, 0xFFD0D4DC, 1);
            drawBtn(W/2.f-160, H*0.66f, 140, 44, "LOBBY", 0);
            drawBtn(W/2.f+20, H*0.66f, 140, 44, "RETRY", 1);
        }
    }
    SDL_RenderPresent(g_ren);
}

static void skillBurst() {
    if (skillCd>0) return;
    skillCd=6.f; shake=0.25f; audio_sfx("audio/sfx/start.ogg",0.7f);
    for (auto& m: wolves) {
        if (hypotf(m.x-px,m.y-py)<2.4f) { m.hp-=0.8f; pop(m.x,m.y-0.3f,"SKILL"); }
    }
    warmth = fmaxf(0, warmth-0.05f);
}

static void onTap(float x, float y, int id, int down) {
    if (!down) {
        if (id==stickId) { stickId=-1; sx=sy=0; }
        dashing=0; atkDown=0;
        return;
    }
    if (state==ST_SPLASH) {
        if (stateT>1.2f) { state=ST_LOAD; stateT=0; loadStep=0; }
        return;
    }
    if (state==ST_MENU) {
        if (inBtn(x,y,W/2.f-130,H*0.42f,260,48)) {
            audio_sfx("audio/sfx/start.ogg",0.8f);
            resetRun(); state=ST_PLAY; prolog();
        } else if (inBtn(x,y,W/2.f-130,H*0.52f,260,48)) { state=ST_SLOTS; }
        else if (inBtn(x,y,W/2.f-130,H*0.62f,260,48)) { state=ST_SETTINGS; }
        return;
    }
    if (state==ST_SETTINGS) {
        if (inBtn(x,y,W/2.f-200,H*0.36f,50,40)) { bgmVol=fmaxf(0,bgmVol-0.1f); audio_set_music_vol(bgmVol); }
        if (inBtn(x,y,W/2.f+150,H*0.36f,50,40)) { bgmVol=fminf(1,bgmVol+0.1f); audio_set_music_vol(bgmVol); }
        if (inBtn(x,y,W/2.f-200,H*0.46f,50,40)) { sfxVol=fmaxf(0,sfxVol-0.1f); audio_set_sfx_vol(sfxVol); }
        if (inBtn(x,y,W/2.f+150,H*0.46f,50,40)) { sfxVol=fminf(1,sfxVol+0.1f); audio_set_sfx_vol(sfxVol); }
        if (inBtn(x,y,W/2.f-100,H*0.78f,200,44)) state=ST_MENU;
        return;
    }
    if (state==ST_SLOTS) {
        for (int i=0;i<3;i++) if (inBtn(x,y,W/2.f-180,H*0.32f+i*70,360,52) && slotExists(i)) {
            if (loadSlot(i)) { state=ST_PLAY; say("Save dimuat."); }
        }
        if (inBtn(x,y,W/2.f-100,H*0.80f,200,44)) state=ST_MENU;
        return;
    }
    if (state==ST_DIALOG) {
        if (dlgI<(int)dialog.size() && dlgType < dialog[dlgI].text.size()) dlgType = (float)dialog[dlgI].text.size();
        else {
            dlgI++; dlgType=0;
            if (dlgI>=(int)dialog.size()) { state=ST_PLAY; dialog.clear(); }
        }
        return;
    }
    if (state==ST_PAUSE) {
        if (inBtn(x,y,W/2.f-156,H*0.55f,140,46)) { state=ST_MENU; resetRun(); }
        if (inBtn(x,y,W/2.f+16,H*0.55f,140,46)) state=ST_PLAY;
        return;
    }
    if (state==ST_DEAD) {
        if (inBtn(x,y,W/2.f-160,H*0.66f,140,44)) { state=ST_MENU; resetRun(); }
        if (inBtn(x,y,W/2.f+20,H*0.66f,140,44)) { resetRun(); state=ST_PLAY; prolog(); }
        return;
    }
    if (state==ST_PLAY) {
        if (hit(x,y, W-34.f, 30, 26)) { state=ST_PAUSE; audio_sfx("audio/sfx/click.ogg",0.7f); return; }
        if (hit(x,y, 104, H-112.f, 82)) { stickId=id; }
        else if (hit(x,y, W-96.f, H-112.f, 60)) { atkDown=1; interact(); }
        else if (hit(x,y, W-196.f, H-80.f, 44)) { if (stamina>0.15f){ dashing=1; dashT=0.28f; iframe=0.22f; } }
        else if (hit(x,y, W-176.f, H-176.f, 42)) {
            // skill if wolves near else eat
            int nearW=0; for (auto& m:wolves) if (hypotf(m.x-px,m.y-py)<3.f) nearW=1;
            if (nearW) skillBurst(); else eat();
        }
    }
}

static void onMove(float x, float y, int id) {
    if (id!=stickId && stickId!=-1 && id!=stickId) return;
    if (stickId==id || (stickId!=-1 && id==stickId)) {
        float dx=x-104.f, dy=y-(H-112.f);
        float len=hypotf(dx,dy);
        if (len>52.f){ dx=dx/len*52.f; dy=dy/len*52.f; }
        sx = fminf(1.f, fmaxf(-1.f, dx/52.f));
        sy = fminf(1.f, fmaxf(-1.f, dy/52.f));
    }
}

extern "C" int SDL_main(int, char**) {
    SDL_SetHint(SDL_HINT_RENDER_SCALE_QUALITY, "1");
    SDL_SetHint(SDL_HINT_ORIENTATIONS, "LandscapeLeft LandscapeRight");
    if (SDL_Init(SDL_INIT_VIDEO | SDL_INIT_EVENTS | SDL_INIT_TIMER) != 0) {
        SDL_Log("SDL_Init %s", SDL_GetError());
        return 1;
    }
    g_win = SDL_CreateWindow("Last Survival", SDL_WINDOWPOS_CENTERED, SDL_WINDOWPOS_CENTERED,
                             1280, 720, SDL_WINDOW_SHOWN | SDL_WINDOW_RESIZABLE | SDL_WINDOW_FULLSCREEN_DESKTOP);
    g_ren = SDL_CreateRenderer(g_win, -1, SDL_RENDERER_ACCELERATED | SDL_RENDERER_PRESENTVSYNC);
    if (!g_ren) g_ren = SDL_CreateRenderer(g_win, -1, 0);
    SDL_GetWindowSize(g_win, &W, &H);
    audio_init();
    buildMap();
    logo = loadTex("ui/logo_hoshidev.png");

    int running=1;
    Uint64 prev = SDL_GetPerformanceCounter();
    double freq = (double)SDL_GetPerformanceFrequency();
    while (running) {
        Uint64 now = SDL_GetPerformanceCounter();
        float dt = (float)((now-prev)/freq);
        prev = now;
        if (dt>0.05f) dt=0.05f;
        stateT += dt;

        SDL_Event e;
        while (SDL_PollEvent(&e)) {
            if (e.type==SDL_QUIT) running=0;
            else if (e.type==SDL_APP_WILLENTERBACKGROUND) { if (state==ST_PLAY) { state=ST_PAUSE; saveSlot(0);} }
            else if (e.type==SDL_KEYDOWN && e.key.keysym.sym==SDLK_AC_BACK) {
                if (state==ST_PLAY) state=ST_PAUSE;
                else if (state==ST_PAUSE||state==ST_SETTINGS||state==ST_SLOTS) state=ST_MENU;
            } else if (e.type==SDL_FINGERDOWN) {
                onTap(e.tfinger.x*W, e.tfinger.y*H, (int)e.tfinger.fingerId, 1);
            } else if (e.type==SDL_FINGERUP) {
                onTap(e.tfinger.x*W, e.tfinger.y*H, (int)e.tfinger.fingerId, 0);
            } else if (e.type==SDL_FINGERMOTION) {
                onMove(e.tfinger.x*W, e.tfinger.y*H, (int)e.tfinger.fingerId);
            } else if (e.type==SDL_MOUSEBUTTONDOWN) {
                onTap((float)e.button.x,(float)e.button.y, 99, 1);
            } else if (e.type==SDL_MOUSEBUTTONUP) {
                onTap((float)e.button.x,(float)e.button.y, 99, 0);
            } else if (e.type==SDL_MOUSEMOTION && (e.motion.state & SDL_BUTTON_LMASK)) {
                onMove((float)e.motion.x,(float)e.motion.y, 99);
            }
        }

        if (state==ST_SPLASH && stateT>=3.0f) { state=ST_LOAD; stateT=0; loadStep=0; }
        if (state==ST_LOAD) {
            if (doLoadStep()) { state=ST_MENU; stateT=0; }
        }
        if (state==ST_PLAY) tick(dt);
        if (state==ST_DIALOG) {
            if (dlgI<(int)dialog.size()) dlgType += dt*36.f;
        }
        if (dashT<=0) dashing=0;
        pumpMusic();
        render();
    }
    audio_shutdown();
    SDL_DestroyRenderer(g_ren);
    SDL_DestroyWindow(g_win);
    SDL_Quit();
    return 0;
}
