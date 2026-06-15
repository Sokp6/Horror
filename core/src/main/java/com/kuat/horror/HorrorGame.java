package com.kuat.horror;

/*
 *  ОСОБНЯК — Super Horror v2 (libGDX / Android)
 *  ------------------------------------------------------------
 *  - 10 уровней, случайный лабиринт каждый раз, сложность растёт
 *  - бег с выносливостью (кнопка RUN, можно держать вместе с D-pad)
 *  - тряска экрана рядом с монстром
 *  - СКРИМЕР + сильная вибрация при смерти
 *  - на поздних уровнях несколько монстров
 *
 *  ВАЖНО: для вибрации нужно разрешение VIBRATE в AndroidManifest.xml
 *  (см. новый манифест, который идёт вместе с этим файлом).
 *
 *  Текст в HUD английский — встроенный шрифт не знает кириллицу.
 */

import com.badlogic.gdx.ApplicationAdapter;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.InputAdapter;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.OrthographicCamera;
import com.badlogic.gdx.graphics.g2d.BitmapFont;
import com.badlogic.gdx.graphics.g2d.SpriteBatch;
import com.badlogic.gdx.graphics.glutils.ShapeRenderer;
import com.badlogic.gdx.math.Matrix4;
import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.utils.viewport.FitViewport;
import com.badlogic.gdx.utils.viewport.Viewport;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

public class HorrorGame extends ApplicationAdapter {

    static final int TILE = 40, COLS = 19, ROWS = 13;
    static final float WORLD_W = 760, PLAY_H = ROWS * TILE, WORLD_H = 760; // play=520, низ=управление
    static final int MAX_LEVEL = 10;

    OrthographicCamera cam;
    Viewport viewport;
    ShapeRenderer sr;
    SpriteBatch batch;
    BitmapFont font;
    final Vector3 touch = new Vector3();
    final Matrix4 tmp = new Matrix4();
    final Random rnd = new Random();

    boolean[][] wall = new boolean[ROWS][COLS];
    Set<Long> keys = new HashSet<>(), batteries = new HashSet<>(), lockers = new HashSet<>();
    List<int[]> monsters = new ArrayList<>();
    int doorX, doorY, px, py, keysGot;

    // прогресс / состояние
    int level = 1;
    boolean hidden, dead, won;
    float clearTimer;     // >0 — показываем "уровень пройден"
    float scareTimer;     // время с момента смерти (для анимации скримера)
    boolean vibrated;

    // ресурсы игрока
    double battery; float stamina; float flicker;

    // параметры сложности уровня
    int keysNeeded, monCount, detectRange, batCount, lockCount;
    float monInterval, batDrain, flashBase;

    // тайминги
    float time, moveAcc, monAcc, shake, vibeTimer;
    String msg = ""; float msgTimer;

    // ввод (мультитач: бег + направление одновременно)
    int heldDir = -1, heldPointer = -1, runPointer = -1;
    boolean runHeld;

    // кнопки {x,y,w,h} в мировых координатах (y вниз)
    final float[] BTN_UP    = { 83, 556, 64, 64};
    final float[] BTN_DOWN  = { 83, 678, 64, 64};
    final float[] BTN_LEFT  = { 13, 617, 64, 64};
    final float[] BTN_RIGHT = {153, 617, 64, 64};
    final float[] BTN_RUN   = {300, 600, 130, 90};
    final float[] BTN_HIDE  = {450, 600, 130, 90};

    static long pack(int x, int y) { return (((long) x) << 32) | (y & 0xffffffffL); }
    static int ux(long p) { return (int) (p >> 32); }
    static int uy(long p) { return (int) (p & 0xffffffffL); }

    @Override public void create() {
        cam = new OrthographicCamera();
        cam.setToOrtho(true, WORLD_W, WORLD_H);
        viewport = new FitViewport(WORLD_W, WORLD_H, cam);
        sr = new ShapeRenderer();
        batch = new SpriteBatch();
        font = new BitmapFont(true);
        font.getData().setScale(1.4f);
        Gdx.input.setInputProcessor(new InputAdapter() {
            @Override public boolean touchDown(int x, int y, int pointer, int button) { return onDown(x, y, pointer); }
            @Override public boolean touchUp(int x, int y, int pointer, int button) { return onUp(pointer); }
        });
        startLevel(1);
    }

    // ===== Запуск уровня =====
    void startLevel(int n) {
        level = n;
        keysNeeded  = Math.min(2 + level / 2, 6);
        monCount    = level <= 4 ? 1 : (level <= 8 ? 2 : 3);
        monInterval = Math.max(0.14f, 0.50f - level * 0.03f + (rnd.nextFloat() * 0.06f - 0.03f));
        detectRange = 4 + level / 2;
        batDrain    = 1.6f + level * 0.08f;
        flashBase   = Math.max(38f, 52f - level * 1.2f);
        batCount    = Math.max(2, 4 - level / 4);
        lockCount   = Math.max(1, 3 - level / 4);

        generateMaze();
        placeStuff();

        px = 1; py = 1; keysGot = 0;
        battery = 100; stamina = 100; flicker = 0;
        hidden = false; dead = false; won = false;
        clearTimer = 0; scareTimer = 0; vibrated = false;
        moveAcc = 0; monAcc = 0; shake = 0; vibeTimer = 0;
        heldDir = -1; heldPointer = -1; runHeld = false; runPointer = -1;
        say("LEVEL " + level + "/" + MAX_LEVEL);
    }

    // ===== Генерация лабиринта (рекурсивный бэктрекинг + немного петель) =====
    void generateMaze() {
        for (int y = 0; y < ROWS; y++) for (int x = 0; x < COLS; x++) wall[y][x] = true;
        ArrayDeque<int[]> st = new ArrayDeque<>();
        wall[1][1] = false; st.push(new int[]{1, 1});
        int[][] d = {{0, -2}, {0, 2}, {-2, 0}, {2, 0}};
        while (!st.isEmpty()) {
            int[] c = st.peek();
            List<int[]> ns = new ArrayList<>();
            for (int[] dd : d) {
                int nx = c[0] + dd[0], ny = c[1] + dd[1];
                if (nx > 0 && ny > 0 && nx < COLS - 1 && ny < ROWS - 1 && wall[ny][nx])
                    ns.add(new int[]{nx, ny});
            }
            if (ns.isEmpty()) { st.pop(); continue; }
            int[] n = ns.get(rnd.nextInt(ns.size()));
            wall[(c[1] + n[1]) / 2][(c[0] + n[0]) / 2] = false;
            wall[n[1]][n[0]] = false;
            st.push(n);
        }
        // петли: открываем часть внутренних стен (меньше на высоких уровнях)
        float loopP = Math.max(0.05f, 0.18f - level * 0.012f);
        for (int y = 1; y < ROWS - 1; y++) for (int x = 1; x < COLS - 1; x++) {
            if (!wall[y][x]) continue;
            boolean h = (x % 2 == 0) && !wall[y][x - 1] && !wall[y][x + 1];
            boolean v = (y % 2 == 0) && !wall[y - 1][x] && !wall[y + 1][x];
            if ((h || v) && rnd.nextFloat() < loopP) wall[y][x] = false;
        }
    }

    int[][] computeDist(int sx, int sy) {
        int[][] dist = new int[ROWS][COLS];
        for (int y = 0; y < ROWS; y++) for (int x = 0; x < COLS; x++) dist[y][x] = -1;
        ArrayDeque<int[]> q = new ArrayDeque<>(); dist[sy][sx] = 0; q.add(new int[]{sx, sy});
        int[][] dir = {{0, -1}, {0, 1}, {-1, 0}, {1, 0}};
        while (!q.isEmpty()) {
            int[] c = q.poll();
            for (int[] dd : dir) {
                int ax = c[0] + dd[0], ay = c[1] + dd[1];
                if (!isWall(ax, ay) && dist[ay][ax] == -1) { dist[ay][ax] = dist[c[1]][c[0]] + 1; q.add(new int[]{ax, ay}); }
            }
        }
        return dist;
    }

    void placeStuff() {
        keys.clear(); batteries.clear(); lockers.clear(); monsters.clear();
        int[][] dist = computeDist(1, 1);
        List<Long> floors = new ArrayList<>();
        for (int y = 0; y < ROWS; y++) for (int x = 0; x < COLS; x++) if (!wall[y][x]) floors.add(pack(x, y));

        long startP = pack(1, 1), doorP = startP; int best = -1;
        for (long p : floors) { int dd = dist[uy(p)][ux(p)]; if (dd > best) { best = dd; doorP = p; } }
        doorX = ux(doorP); doorY = uy(doorP);

        Collections.shuffle(floors, rnd);
        Set<Long> used = new HashSet<>(); used.add(startP); used.add(doorP);

        int placed = 0;
        for (long p : floors) { if (placed >= keysNeeded) break; if (used.contains(p)) continue; keys.add(p); used.add(p); placed++; }
        placed = 0;
        for (long p : floors) { if (placed >= batCount) break; if (used.contains(p)) continue; batteries.add(p); used.add(p); placed++; }
        placed = 0;
        for (long p : floors) { if (placed >= lockCount) break; if (used.contains(p)) continue; lockers.add(p); used.add(p); placed++; }
        placed = 0;
        for (long p : floors) {
            if (placed >= monCount) break;
            if (used.contains(p) || dist[uy(p)][ux(p)] < 8) continue;
            monsters.add(new int[]{ux(p), uy(p)}); used.add(p); placed++;
        }
        for (long p : floors) { // добор если далёких тайлов не хватило
            if (placed >= monCount) break;
            if (used.contains(p)) continue;
            monsters.add(new int[]{ux(p), uy(p)}); used.add(p); placed++;
        }
    }

    void say(String s) { msg = s; msgTimer = 2.2f; }
    boolean isWall(int x, int y) { if (x < 0 || y < 0 || x >= COLS || y >= ROWS) return true; return wall[y][x]; }
    void vibrate(int ms) { try { Gdx.input.vibrate(ms); } catch (Throwable ignored) {} }

    int nearest() {
        int b = 999;
        for (int[] m : monsters) b = Math.min(b, Math.abs(px - m[0]) + Math.abs(py - m[1]));
        return b;
    }

    void die() {
        if (dead) return;
        dead = true; scareTimer = 0; vibrated = false; shake = 28;
        heldDir = -1; runHeld = false;
    }

    // ===== Кадр =====
    @Override public void render() {
        float dt = Math.min(Gdx.graphics.getDeltaTime(), 0.05f);
        update(dt);
        Gdx.gl.glClearColor(0.04f, 0.04f, 0.05f, 1);
        Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT);
        viewport.apply();
        draw();
    }

    void update(float dt) {
        time += dt;
        if (msgTimer > 0) msgTimer -= dt;
        if (shake > 0) shake = Math.max(0, shake - dt * 22);

        if (dead) {
            scareTimer += dt;
            if (!vibrated) { vibrate(1200); vibrated = true; }
            return;
        }
        if (won) return;
        if (clearTimer > 0) {
            clearTimer -= dt;
            if (clearTimer <= 0) { if (level >= MAX_LEVEL) won = true; else startLevel(level + 1); }
            return;
        }

        boolean moving = heldDir >= 0;
        boolean effRun = runHeld && stamina > 0;
        if (effRun && moving) stamina = Math.max(0, stamina - dt * 32);
        else stamina = Math.min(100, stamina + dt * 20);

        if (!hidden) battery = Math.max(0, battery - dt * batDrain);
        flicker = battery < 25 ? (rnd.nextFloat() * 20 - 10) : 0;

        if (heldDir >= 0) {
            moveAcc += dt;
            float interval = effRun ? 0.07f : 0.16f;
            if (moveAcc >= interval) { moveAcc = 0; tryMove(heldDir); }
        }

        monAcc += dt;
        if (monAcc >= monInterval) { monAcc = 0; monsterStep(); }

        int nd = nearest();
        if (!hidden && nd <= 3) {
            shake = Math.max(shake, (4 - nd) * 1.5f);
            vibeTimer += dt;
            float pulse = nd <= 1 ? 0.28f : nd <= 2 ? 0.45f : 0.7f;
            if (vibeTimer >= pulse) { vibeTimer = 0; vibrate(55); }
        } else vibeTimer = 0;
    }

    void tryMove(int dir) {
        if (hidden || dead || won || clearTimer > 0) return;
        int nx = px, ny = py;
        if (dir == 0) ny--; else if (dir == 1) ny++; else if (dir == 2) nx--; else if (dir == 3) nx++;
        if (!isWall(nx, ny)) { px = nx; py = ny; afterMove(); }
    }

    void afterMove() {
        long p = pack(px, py);
        if (keys.remove(p)) { keysGot++; say("KEY " + keysGot + "/" + keysNeeded); }
        if (batteries.remove(p)) { battery = Math.min(100, battery + 45); say("BATTERY +45%"); }
        if (px == doorX && py == doorY) {
            if (keysGot >= keysNeeded) { if (level >= MAX_LEVEL) won = true; else { clearTimer = 1.6f; say("LEVEL CLEARED"); } }
            else say("DOOR LOCKED " + keysGot + "/" + keysNeeded);
        }
        for (int[] m : monsters) if (m[0] == px && m[1] == py && !hidden) { die(); return; }
    }

    void toggleHide() {
        if (dead || won || clearTimer > 0) return;
        if (lockers.contains(pack(px, py))) {
            hidden = !hidden;
            if (!hidden) for (int[] m : monsters) if (m[0] == px && m[1] == py) { die(); return; }
            heldDir = -1; runHeld = false;
            say(hidden ? "HIDDEN. STAY QUIET" : "OUT");
        } else say("NO LOCKER HERE");
    }

    void monsterStep() {
        for (int[] m : monsters) {
            boolean chase = !hidden && (Math.abs(px - m[0]) + Math.abs(py - m[1])) <= detectRange;
            int[] n = chase ? bfsStep(m[0], m[1], px, py) : wander(m[0], m[1]);
            if (n != null) { m[0] = n[0]; m[1] = n[1]; }
            if (m[0] == px && m[1] == py && !hidden) { die(); return; }
        }
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

    // ===== Отрисовка =====
    void draw() {
        float ox = shake > 0 ? (rnd.nextFloat() * 2 - 1) * shake : 0;
        float oy = shake > 0 ? (rnd.nextFloat() * 2 - 1) * shake : 0;
        Matrix4 shaken = tmp.set(cam.combined).translate(ox, oy, 0);

        sr.setProjectionMatrix(shaken);
        Gdx.gl.glEnable(GL20.GL_BLEND);

        // мир
        sr.begin(ShapeRenderer.ShapeType.Filled);
        for (int y = 0; y < ROWS; y++) for (int x = 0; x < COLS; x++) {
            if (wall[y][x]) c(28, 28, 34, 1); else c(18, 18, 22, 1);
            sr.rect(x * TILE, y * TILE, TILE, TILE);
        }
        for (long p : lockers) { c(80, 55, 30, 1); sr.rect(ux(p) * TILE + 8, uy(p) * TILE + 4, TILE - 16, TILE - 6); }
        if (keysGot >= keysNeeded) c(60, 200, 90, 1); else c(120, 40, 40, 1);
        sr.rect(doorX * TILE + 6, doorY * TILE + 4, TILE - 12, TILE - 6);
        c(240, 210, 60, 1);
        for (long p : keys) sr.circle(ux(p) * TILE + TILE / 2f, uy(p) * TILE + TILE / 2f, (TILE - 24) / 2f);
        c(70, 210, 120, 1);
        for (long p : batteries) sr.rect(ux(p) * TILE + 13, uy(p) * TILE + 10, TILE - 26, TILE - 20);
        for (int[] m : monsters) {
            c(160, 20, 20, 1); sr.circle(m[0] * TILE + TILE / 2f, m[1] * TILE + TILE / 2f, (TILE - 12) / 2f);
            c(255, 230, 0, 1);
            sr.circle(m[0] * TILE + 15, m[1] * TILE + 17, 3); sr.circle(m[0] * TILE + TILE - 15, m[1] * TILE + 17, 3);
        }
        if (hidden) c(60, 60, 60, 1); else c(120, 200, 255, 1);
        sr.circle(px * TILE + TILE / 2f, py * TILE + TILE / 2f, (TILE - 18) / 2f);
        sr.end();

        // фонарь
        sr.begin(ShapeRenderer.ShapeType.Filled);
        float lr = hidden ? 55 : (flashBase + (float) battery * 1.5f + flicker); if (lr < 32) lr = 32;
        float pcx = px * TILE + TILE / 2f, pcy = py * TILE + TILE / 2f, inner = lr * 0.55f;
        for (int y = 0; y < ROWS; y++) for (int x = 0; x < COLS; x++) {
            float dx = (x * TILE + TILE / 2f) - pcx, dy = (y * TILE + TILE / 2f) - pcy;
            float d = (float) Math.sqrt(dx * dx + dy * dy), a;
            if (d <= inner) a = 0; else if (d >= lr) a = 1; else a = (d - inner) / (lr - inner);
            if (a > 0) { sr.setColor(0, 0, 0, a); sr.rect(x * TILE, y * TILE, TILE, TILE); }
        }
        int nd = nearest();
        if (!hidden && nd <= 4 && !dead) { sr.setColor(0.6f, 0, 0, 0.22f); sr.rect(0, 0, WORLD_W, PLAY_H); }
        sr.end();

        // панель управления (без тряски)
        sr.setProjectionMatrix(cam.combined);
        sr.begin(ShapeRenderer.ShapeType.Filled);
        c(8, 8, 10, 1); sr.rect(0, PLAY_H, WORLD_W, WORLD_H - PLAY_H);
        c(40, 40, 52, 1);
        for (float[] b : new float[][]{BTN_UP, BTN_DOWN, BTN_LEFT, BTN_RIGHT}) sr.rect(b[0], b[1], b[2], b[3]);
        if (runHeld && stamina > 0) c(40, 90, 120, 1); else c(35, 45, 55, 1);
        sr.rect(BTN_RUN[0], BTN_RUN[1], BTN_RUN[2], BTN_RUN[3]);
        if (hidden) c(95, 35, 35, 1); else c(55, 30, 30, 1);
        sr.rect(BTN_HIDE[0], BTN_HIDE[1], BTN_HIDE[2], BTN_HIDE[3]);
        // батарея
        c(60, 60, 70, 1); sr.rect(600, 556, 145, 14);
        if (battery > 40) c(70, 210, 120, 1); else if (battery > 15) c(230, 200, 60, 1); else c(220, 60, 60, 1);
        sr.rect(600, 556, (float) (145 * battery / 100), 14);
        // выносливость
        c(60, 60, 70, 1); sr.rect(600, 578, 145, 14);
        c(90, 150, 230, 1); sr.rect(600, 578, 145 * stamina / 100f, 14);
        sr.end();
        Gdx.gl.glDisable(GL20.GL_BLEND);

        // скример
        if (dead) {
            sr.setProjectionMatrix(shaken);
            Gdx.gl.glEnable(GL20.GL_BLEND);
            sr.begin(ShapeRenderer.ShapeType.Filled);
            drawJumpscare();
            sr.end();
            Gdx.gl.glDisable(GL20.GL_BLEND);
        }

        // текст (без тряски)
        batch.setProjectionMatrix(cam.combined);
        batch.begin();
        font.getData().setScale(1.4f);
        font.setColor(1, 0.82f, 0.24f, 1); font.draw(batch, "LVL " + level + "/" + MAX_LEVEL, 16, 548);
        font.draw(batch, "KEYS " + keysGot + "/" + keysNeeded, 200, 548);
        font.setColor(0.8f, 0.85f, 0.95f, 1);
        font.draw(batch, "LIGHT", 600, 552);
        font.draw(batch, "RUN", 600, 575);
        font.setColor(1, 1, 1, 1);
        font.draw(batch, "^", BTN_UP[0] + 28, BTN_UP[1] + 44);
        font.draw(batch, "v", BTN_DOWN[0] + 28, BTN_DOWN[1] + 44);
        font.draw(batch, "<", BTN_LEFT[0] + 28, BTN_LEFT[1] + 44);
        font.draw(batch, ">", BTN_RIGHT[0] + 28, BTN_RIGHT[1] + 44);
        font.draw(batch, "RUN", BTN_RUN[0] + 38, BTN_RUN[1] + 56);
        font.draw(batch, "HIDE", BTN_HIDE[0] + 35, BTN_HIDE[1] + 56);
        if (msgTimer > 0) { font.setColor(1, 0.45f, 0.45f, 1); font.draw(batch, msg, 16, 590); }

        if (clearTimer > 0) {
            font.getData().setScale(2.6f);
            font.setColor(0.27f, 0.86f, 0.47f, 1);
            font.draw(batch, "LEVEL " + level + " CLEARED", WORLD_W / 2 - 220, PLAY_H / 2);
        }
        if (dead) {
            font.getData().setScale(3.2f);
            font.setColor(1, 0.1f, 0.1f, 1);
            font.draw(batch, "YOU DIED", WORLD_W / 2 - 165, PLAY_H / 2 - 120);
            font.getData().setScale(1.6f);
            font.setColor(1, 1, 1, 1);
            font.draw(batch, "tap to retry", WORLD_W / 2 - 70, PLAY_H / 2 + 150);
        }
        if (won) {
            font.getData().setScale(2.4f);
            font.setColor(0.27f, 0.86f, 0.47f, 1);
            font.draw(batch, "YOU ESCAPED ALL 10", WORLD_W / 2 - 250, PLAY_H / 2 - 30);
            font.getData().setScale(1.6f);
            font.setColor(1, 1, 1, 1);
            font.draw(batch, "tap to play again", WORLD_W / 2 - 95, PLAY_H / 2 + 30);
        }
        font.getData().setScale(1.4f);
        batch.end();
    }

    // детальный скример из примитивов (растёт и дёргается)
    void drawJumpscare() {
        float t = scareTimer;
        float grow = Math.min(1f, t / 0.35f);
        // стробящий красно-чёрный фон
        float strobe = 0.45f + 0.4f * (float) Math.abs(Math.sin(t * 28));
        sr.setColor(0.55f, 0f, 0f, strobe); sr.rect(0, 0, WORLD_W, PLAY_H);
        sr.setColor(0f, 0f, 0f, 0.35f); sr.rect(0, 0, WORLD_W, PLAY_H);

        float cx = WORLD_W / 2f, cy = PLAY_H / 2f;
        float s = grow * (1f + 0.04f * (float) Math.sin(t * 22)); // лёгкая пульсация
        float head = 150 * s;

        // голова
        sr.setColor(0.06f, 0.02f, 0.02f, 1); sr.circle(cx, cy, head);
        sr.setColor(0.16f, 0.03f, 0.03f, 1); sr.circle(cx, cy, head * 0.92f);

        // глаза (светящиеся)
        float ex = head * 0.42f, ey = head * 0.22f, er = head * 0.26f;
        sr.setColor(1f, 0.9f, 0.2f, 1);
        sr.circle(cx - ex, cy - ey, er); sr.circle(cx + ex, cy - ey, er);
        sr.setColor(0.9f, 0.1f, 0.05f, 1);
        sr.circle(cx - ex, cy - ey, er * 0.6f); sr.circle(cx + ex, cy - ey, er * 0.6f);
        sr.setColor(0, 0, 0, 1);
        sr.circle(cx - ex, cy - ey, er * 0.26f); sr.circle(cx + ex, cy - ey, er * 0.26f);

        // злые брови
        sr.setColor(0, 0, 0, 1);
        sr.triangle(cx - head * 0.7f, cy - head * 0.62f, cx - head * 0.12f, cy - head * 0.36f, cx - head * 0.66f, cy - head * 0.34f);
        sr.triangle(cx + head * 0.7f, cy - head * 0.62f, cx + head * 0.12f, cy - head * 0.36f, cx + head * 0.66f, cy - head * 0.34f);

        // пасть
        float mw = head * 0.78f, mtop = cy + head * 0.18f, mbot = cy + head * 0.72f;
        sr.setColor(0, 0, 0, 1);
        sr.rect(cx - mw / 2, mtop, mw, mbot - mtop);
        // зубы (треугольники сверху и снизу)
        sr.setColor(0.92f, 0.92f, 0.85f, 1);
        int teeth = 7;
        float tw = mw / teeth;
        for (int i = 0; i < teeth; i++) {
            float lx = cx - mw / 2 + i * tw;
            sr.triangle(lx, mtop, lx + tw, mtop, lx + tw / 2, mtop + head * 0.22f);            // верхние
            sr.triangle(lx, mbot, lx + tw, mbot, lx + tw / 2, mbot - head * 0.22f);            // нижние
        }
    }

    void c(int r, int g, int b, float a) { sr.setColor(r / 255f, g / 255f, b / 255f, a); }
    boolean in(float[] r, float x, float y) { return x >= r[0] && x <= r[0] + r[2] && y >= r[1] && y <= r[1] + r[3]; }

    boolean onDown(int sx, int sy, int pointer) {
        touch.set(sx, sy, 0); viewport.unproject(touch);
        float x = touch.x, y = touch.y;
        if (dead || won) { startLevel(dead ? level : 1); return true; }
        if (clearTimer > 0) return true;
        if (in(BTN_HIDE, x, y)) { toggleHide(); return true; }
        if (in(BTN_RUN, x, y)) { runHeld = true; runPointer = pointer; return true; }
        int dir = -1;
        if (in(BTN_UP, x, y)) dir = 0; else if (in(BTN_DOWN, x, y)) dir = 1;
        else if (in(BTN_LEFT, x, y)) dir = 2; else if (in(BTN_RIGHT, x, y)) dir = 3;
        if (dir >= 0) { heldDir = dir; heldPointer = pointer; moveAcc = 0; tryMove(dir); }
        return true;
    }

    boolean onUp(int pointer) {
        if (pointer == heldPointer) { heldDir = -1; heldPointer = -1; }
        if (pointer == runPointer) { runHeld = false; runPointer = -1; }
        return true;
    }

    @Override public void resize(int w, int h) { viewport.update(w, h, true); }
    @Override public void dispose() { sr.dispose(); batch.dispose(); font.dispose(); }
}
