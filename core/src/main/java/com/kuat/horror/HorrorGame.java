package com.kuat.horror; // <-- ЗАМЕНИ на package своего проекта из liftoff (если другой)

/*
 *  ОСОБНЯК — Super Horror (libGDX / Android)
 *  ------------------------------------------------------------
 *  Порт десктопной версии под телефон. Тач-управление:
 *    - крестовина (D-pad) слева: держи кнопку — идёшь
 *    - кнопка HIDE справа: спрятаться/выйти (стоя на шкафу)
 *    - после смерти/победы: тапни в любом месте — заново
 *
 *  ВАЖНО: имя класса должно совпадать с main-классом, который
 *  liftoff прописал в лаунчерах (Android/Desktop). Если в liftoff
 *  ты назвал main class иначе (напр. Main) — переименуй
 *  "class HorrorGame" -> "class Main" ниже, либо в liftoff укажи
 *  Main class = HorrorGame.
 *
 *  Шрифт по умолчанию в libGDX знает только латиницу, поэтому
 *  весь текст тут английский. Как сделать русский — см. SETUP.md.
 */

import com.badlogic.gdx.ApplicationAdapter;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.InputAdapter;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.OrthographicCamera;
import com.badlogic.gdx.graphics.g2d.BitmapFont;
import com.badlogic.gdx.graphics.g2d.SpriteBatch;
import com.badlogic.gdx.graphics.glutils.ShapeRenderer;
import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.utils.viewport.FitViewport;
import com.badlogic.gdx.utils.viewport.Viewport;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

public class HorrorGame extends ApplicationAdapter {

    static final int TILE = 40, COLS = 19, ROWS = 13, KEYS_NEEDED = 3;
    static final float WORLD_W = 760, PLAY_H = ROWS * TILE, WORLD_H = 760; // play=520, низ=управление

    OrthographicCamera cam;
    Viewport viewport;
    ShapeRenderer sr;
    SpriteBatch batch;
    BitmapFont font;
    final Vector3 touch = new Vector3();

    boolean[][] wall = new boolean[ROWS][COLS];
    Set<Long> keys = new HashSet<>(), batteries = new HashSet<>(), lockers = new HashSet<>();
    int doorX, doorY, px, py, mx, my, keysGot;
    boolean hidden, dead, won;
    double battery; float flicker;
    String msg = ""; float msgTimer;
    float moveAcc, monAcc;
    int heldDir = -1, heldPointer = -1; // 0 up 1 down 2 left 3 right
    final Random rnd = new Random();

    // UI-прямоугольники {x, y, w, h} в мировых координатах (y вниз)
    final float[] BTN_UP    = {120 - 35, 590 - 35, 70, 70};
    final float[] BTN_DOWN  = {120 - 35, 700 - 35, 70, 70};
    final float[] BTN_LEFT  = { 45 - 35, 645 - 35, 70, 70};
    final float[] BTN_RIGHT = {195 - 35, 645 - 35, 70, 70};
    final float[] BTN_HIDE  = {545, 600, 165, 95};

    static long pack(int x, int y) { return (((long) x) << 32) | (y & 0xffffffffL); }
    static int ux(long p) { return (int) (p >> 32); }
    static int uy(long p) { return (int) (p & 0xffffffffL); }

    @Override public void create() {
        cam = new OrthographicCamera();
        cam.setToOrtho(true, WORLD_W, WORLD_H);          // y вниз — как в десктопной версии
        viewport = new FitViewport(WORLD_W, WORLD_H, cam);
        sr = new ShapeRenderer();
        batch = new SpriteBatch();
        font = new BitmapFont(true);                     // flip=true для y-вниз камеры
        font.getData().setScale(1.4f);
        Gdx.input.setInputProcessor(new InputAdapter() {
            @Override public boolean touchDown(int x, int y, int pointer, int button) { return onDown(x, y, pointer); }
            @Override public boolean touchUp(int x, int y, int pointer, int button) {
                if (pointer == heldPointer) { heldDir = -1; heldPointer = -1; }
                return true;
            }
        });
        reset();
    }

    void reset() {
        for (int y = 0; y < ROWS; y++) for (int x = 0; x < COLS; x++) wall[y][x] = false;
        keys.clear(); batteries.clear(); lockers.clear();
        for (int x = 0; x < COLS; x++) { wall[0][x] = true; wall[ROWS - 1][x] = true; }
        for (int y = 0; y < ROWS; y++) { wall[y][0] = true; wall[y][COLS - 1] = true; }
        for (int y = 2; y < ROWS - 1; y += 2) for (int x = 2; x < COLS - 1; x += 2) wall[y][x] = true;
        keys.add(pack(1, 11)); keys.add(pack(9, 11)); keys.add(pack(17, 5));
        batteries.add(pack(1, 5)); batteries.add(pack(9, 1)); batteries.add(pack(13, 9));
        lockers.add(pack(5, 7)); lockers.add(pack(13, 3)); lockers.add(pack(7, 1));
        doorX = 17; doorY = 1;
        px = 1; py = 1; mx = 17; my = 11;
        hidden = false; keysGot = 0; battery = 100; dead = false; won = false; flicker = 0;
        moveAcc = monAcc = 0; heldDir = -1; heldPointer = -1;
        say("FIND 3 KEYS. ESCAPE.");
    }

    void say(String s) { msg = s; msgTimer = 2.5f; }
    boolean isWall(int x, int y) { if (x < 0 || y < 0 || x >= COLS || y >= ROWS) return true; return wall[y][x]; }

    @Override public void render() {
        float dt = Math.min(Gdx.graphics.getDeltaTime(), 0.05f);
        update(dt);
        Gdx.gl.glClearColor(0.04f, 0.04f, 0.05f, 1);
        Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT);
        viewport.apply();
        draw();
    }

    void update(float dt) {
        if (msgTimer > 0) msgTimer -= dt;
        if (dead || won) return;
        if (!hidden) battery = Math.max(0, battery - dt * 1.8);
        flicker = (battery < 25) ? (rnd.nextFloat() * 20 - 10) : 0;
        if (heldDir >= 0) { moveAcc += dt; if (moveAcc >= 0.14f) { moveAcc = 0; tryMove(heldDir); } }
        monAcc += dt; if (monAcc >= 0.45f) { monAcc = 0; monsterStep(); }
    }

    void tryMove(int dir) {
        if (hidden || dead || won) return;
        int nx = px, ny = py;
        if (dir == 0) ny--; else if (dir == 1) ny++; else if (dir == 2) nx--; else if (dir == 3) nx++;
        if (!isWall(nx, ny)) { px = nx; py = ny; afterMove(); }
    }

    void afterMove() {
        long p = pack(px, py);
        if (keys.remove(p)) { keysGot++; say("KEY " + keysGot + "/" + KEYS_NEEDED); }
        if (batteries.remove(p)) { battery = Math.min(100, battery + 45); say("BATTERY +45%"); }
        if (px == doorX && py == doorY) {
            if (keysGot >= KEYS_NEEDED) won = true; else say("DOOR LOCKED " + keysGot + "/" + KEYS_NEEDED);
        }
        if (px == mx && py == my) dead = true;
    }

    void toggleHide() {
        if (dead || won) return;
        if (lockers.contains(pack(px, py))) {
            hidden = !hidden;
            if (!hidden && px == mx && py == my) { dead = true; return; }
            heldDir = -1; heldPointer = -1;
            say(hidden ? "HIDDEN. STAY QUIET" : "OUT");
        } else say("NO LOCKER HERE");
    }

    void monsterStep() {
        boolean chase = !hidden && (Math.abs(px - mx) + Math.abs(py - my)) <= 7;
        int[] n = chase ? bfsStep(mx, my, px, py) : wander(mx, my);
        if (n != null) { mx = n[0]; my = n[1]; }
        if (mx == px && my == py && !hidden) dead = true;
    }

    int[] bfsStep(int sx, int sy, int tx, int ty) {
        boolean[][] seen = new boolean[ROWS][COLS];
        int[][] fx = new int[ROWS][COLS], fy = new int[ROWS][COLS];
        ArrayDeque<int[]> q = new ArrayDeque<>(); q.add(new int[]{sx, sy}); seen[sy][sx] = true;
        int[][] dir = {{0, -1}, {0, 1}, {-1, 0}, {1, 0}};
        while (!q.isEmpty()) {
            int[] c = q.poll();
            if (c[0] == tx && c[1] == ty) {
                int cx = tx, cy = ty;
                while (!(fx[cy][cx] == sx && fy[cy][cx] == sy)) { int ax = fx[cy][cx], ay = fy[cy][cx]; cx = ax; cy = ay; }
                return new int[]{cx, cy};
            }
            for (int[] dd : dir) {
                int ax = c[0] + dd[0], ay = c[1] + dd[1];
                if (isWall(ax, ay) || seen[ay][ax]) continue;
                seen[ay][ax] = true; fx[ay][ax] = c[0]; fy[ay][ax] = c[1]; q.add(new int[]{ax, ay});
            }
        }
        return wander(sx, sy);
    }

    int[] wander(int x, int y) {
        int[][] dir = {{0, -1}, {0, 1}, {-1, 0}, {1, 0}};
        List<int[]> o = new ArrayList<>();
        for (int[] dd : dir) { int ax = x + dd[0], ay = y + dd[1]; if (!isWall(ax, ay)) o.add(new int[]{ax, ay}); }
        return o.isEmpty() ? null : o.get(rnd.nextInt(o.size()));
    }

    // ===== отрисовка =====
    void draw() {
        sr.setProjectionMatrix(cam.combined);
        Gdx.gl.glEnable(GL20.GL_BLEND);

        sr.begin(ShapeRenderer.ShapeType.Filled);
        for (int y = 0; y < ROWS; y++) for (int x = 0; x < COLS; x++) {
            if (wall[y][x]) c(28, 28, 34, 1); else c(18, 18, 22, 1);
            sr.rect(x * TILE, y * TILE, TILE, TILE);
        }
        for (long p : lockers) { c(80, 55, 30, 1); sr.rect(ux(p) * TILE + 8, uy(p) * TILE + 4, TILE - 16, TILE - 6); }
        if (keysGot >= KEYS_NEEDED) c(60, 200, 90, 1); else c(120, 40, 40, 1);
        sr.rect(doorX * TILE + 6, doorY * TILE + 4, TILE - 12, TILE - 6);
        c(240, 210, 60, 1);
        for (long p : keys) sr.circle(ux(p) * TILE + TILE / 2f, uy(p) * TILE + TILE / 2f, (TILE - 24) / 2f);
        c(70, 210, 120, 1);
        for (long p : batteries) sr.rect(ux(p) * TILE + 13, uy(p) * TILE + 10, TILE - 26, TILE - 20);
        c(160, 20, 20, 1); sr.circle(mx * TILE + TILE / 2f, my * TILE + TILE / 2f, (TILE - 12) / 2f);
        c(255, 230, 0, 1);
        sr.circle(mx * TILE + 15, my * TILE + 17, 3); sr.circle(mx * TILE + TILE - 15, my * TILE + 17, 3);
        if (hidden) c(60, 60, 60, 1); else c(120, 200, 255, 1);
        sr.circle(px * TILE + TILE / 2f, py * TILE + TILE / 2f, (TILE - 18) / 2f);
        sr.end();

        // фонарь: затемнение по тайлам
        sr.begin(ShapeRenderer.ShapeType.Filled);
        float lr = hidden ? 55 : (45 + (float) battery * 1.7f + flicker); if (lr < 35) lr = 35;
        float pcx = px * TILE + TILE / 2f, pcy = py * TILE + TILE / 2f, inner = lr * 0.55f;
        for (int y = 0; y < ROWS; y++) for (int x = 0; x < COLS; x++) {
            float dx = (x * TILE + TILE / 2f) - pcx, dy = (y * TILE + TILE / 2f) - pcy;
            float dist = (float) Math.sqrt(dx * dx + dy * dy), a;
            if (dist <= inner) a = 0; else if (dist >= lr) a = 1; else a = (dist - inner) / (lr - inner);
            if (a > 0) { sr.setColor(0, 0, 0, a); sr.rect(x * TILE, y * TILE, TILE, TILE); }
        }
        int dd = Math.abs(px - mx) + Math.abs(py - my);
        if (!hidden && dd <= 4 && !dead) { sr.setColor(0.6f, 0, 0, 0.22f); sr.rect(0, 0, WORLD_W, PLAY_H); }
        if (dead) { sr.setColor(0.5f, 0, 0, 0.55f); sr.rect(0, 0, WORLD_W, PLAY_H); }
        if (won)  { sr.setColor(0, 0, 0, 0.6f);     sr.rect(0, 0, WORLD_W, PLAY_H); }
        sr.end();

        // панель управления
        sr.begin(ShapeRenderer.ShapeType.Filled);
        c(8, 8, 10, 1); sr.rect(0, PLAY_H, WORLD_W, WORLD_H - PLAY_H);
        c(40, 40, 52, 1);
        for (float[] b : new float[][]{BTN_UP, BTN_DOWN, BTN_LEFT, BTN_RIGHT}) sr.rect(b[0], b[1], b[2], b[3]);
        if (hidden) c(95, 35, 35, 1); else c(55, 30, 30, 1);
        sr.rect(BTN_HIDE[0], BTN_HIDE[1], BTN_HIDE[2], BTN_HIDE[3]);
        c(60, 60, 70, 1); sr.rect(330, 542, 150, 16);
        if (battery > 40) c(70, 210, 120, 1); else if (battery > 15) c(230, 200, 60, 1); else c(220, 60, 60, 1);
        sr.rect(330, 542, (float) (150 * battery / 100), 16);
        sr.end();
        Gdx.gl.glDisable(GL20.GL_BLEND);

        // текст
        batch.setProjectionMatrix(cam.combined);
        batch.begin();
        font.setColor(1, 0.82f, 0.24f, 1); font.draw(batch, "KEYS " + keysGot + "/" + KEYS_NEEDED, 16, 552);
        font.setColor(1, 1, 1, 1); font.draw(batch, "LIGHT", 250, 552);
        font.draw(batch, "^", BTN_UP[0] + 28, BTN_UP[1] + 44);
        font.draw(batch, "v", BTN_DOWN[0] + 28, BTN_DOWN[1] + 44);
        font.draw(batch, "<", BTN_LEFT[0] + 28, BTN_LEFT[1] + 44);
        font.draw(batch, ">", BTN_RIGHT[0] + 28, BTN_RIGHT[1] + 44);
        font.draw(batch, "HIDE", BTN_HIDE[0] + 50, BTN_HIDE[1] + 58);
        if (msgTimer > 0) { font.setColor(1, 0.45f, 0.45f, 1); font.draw(batch, msg, 16, 588); }
        if (dead) {
            font.setColor(1, 0.12f, 0.12f, 1); font.draw(batch, "YOU DIED", WORLD_W / 2 - 80, PLAY_H / 2 - 10);
            font.setColor(1, 1, 1, 1); font.draw(batch, "tap to restart", WORLD_W / 2 - 75, PLAY_H / 2 + 30);
        }
        if (won) {
            font.setColor(0.27f, 0.86f, 0.47f, 1); font.draw(batch, "YOU ESCAPED", WORLD_W / 2 - 100, PLAY_H / 2 - 10);
            font.setColor(1, 1, 1, 1); font.draw(batch, "tap to restart", WORLD_W / 2 - 75, PLAY_H / 2 + 30);
        }
        batch.end();
    }

    void c(int r, int g, int b, float a) { sr.setColor(r / 255f, g / 255f, b / 255f, a); }
    boolean in(float[] r, float x, float y) { return x >= r[0] && x <= r[0] + r[2] && y >= r[1] && y <= r[1] + r[3]; }

    boolean onDown(int sx, int sy, int pointer) {
        touch.set(sx, sy, 0); viewport.unproject(touch);
        float x = touch.x, y = touch.y;
        if (dead || won) { reset(); return true; }
        if (in(BTN_HIDE, x, y)) { toggleHide(); return true; }
        int dir = -1;
        if (in(BTN_UP, x, y)) dir = 0; else if (in(BTN_DOWN, x, y)) dir = 1;
        else if (in(BTN_LEFT, x, y)) dir = 2; else if (in(BTN_RIGHT, x, y)) dir = 3;
        if (dir >= 0) { heldDir = dir; heldPointer = pointer; moveAcc = 0; tryMove(dir); }
        return true;
    }

    @Override public void resize(int w, int h) { viewport.update(w, h, true); }
    @Override public void dispose() { sr.dispose(); batch.dispose(); font.dispose(); }
}
