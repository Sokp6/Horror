package com.kuat.horror;

/*
 *  ОСОБНЯК — Super Horror v3 (libGDX / Android)
 *  ------------------------------------------------------------
 *  FULLSCREEN: карта на весь экран, управление полупрозрачным слоем поверх.
 *  Камера следит за игроком. Лабиринт больше чем экран.
 *
 *  - 10 уровней, случайный лабиринт, сложность растёт
 *  - бег + выносливость (кнопка RUN)
 *  - тряска экрана + вибрация рядом с монстром
 *  - скример + жёсткая вибрация при смерти
 *  - несколько монстров на поздних уровнях
 */

import com.badlogic.gdx.ApplicationAdapter;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.InputAdapter;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.OrthographicCamera;
import com.badlogic.gdx.graphics.g2d.BitmapFont;
import com.badlogic.gdx.graphics.g2d.SpriteBatch;
import com.badlogic.gdx.graphics.glutils.ShapeRenderer;
import com.badlogic.gdx.math.MathUtils;
import com.badlogic.gdx.math.Matrix4;
import com.badlogic.gdx.math.Vector3;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

public class HorrorGame extends ApplicationAdapter {

    // ===== Карта (большая!) =====
    static final int TILE = 48;
    static final int COLS = 31, ROWS = 31;       // 31x31 = огромный лабиринт
    static final int MAP_W = COLS * TILE, MAP_H = ROWS * TILE;
    static final int MAX_LEVEL = 10;

    // ===== Камера =====
    OrthographicCamera gameCam, uiCam;
    float screenW, screenH;
    final Vector3 touch = new Vector3();
    final Matrix4 tmp = new Matrix4();
    final Random rnd = new Random();

    // ===== Рендер =====
    ShapeRenderer sr;
    SpriteBatch batch;
    BitmapFont font, fontBig;

    // ===== Карта =====
    boolean[][] wall = new boolean[ROWS][COLS];
    Set<Long> keys = new HashSet<>(), batteries = new HashSet<>(), lockers = new HashSet<>();
    List<int[]> monsters = new ArrayList<>();
    int doorX, doorY, px, py, keysGot;

    // ===== Состояние =====
    int level = 1;
    boolean hidden, dead, won;
    float clearTimer, scareTimer;
    boolean vibrated;
    double battery; float stamina, flicker;

    // ===== Параметры уровня =====
    int keysNeeded, monCount, detectRange, batCount, lockCount;
    float monInterval, batDrain, flashBase;

    // ===== Тайминги =====
    float time, moveAcc, monAcc, shake, vibeTimer;
    String msg = ""; float msgTimer;

    // ===== Ввод =====
    int heldDir = -1, heldPointer = -1, runPointer = -1;
    boolean runHeld;

    // ===== UI размеры (вычисляются в resize) =====
    float dpadCx, dpadCy, dpadR, btnR;
    float runCx, runCy, runR;
    float hideCx, hideCy, hideR;

    static long pack(int x, int y) { return (((long) x) << 32) | (y & 0xffffffffL); }
    static int ux(long p) { return (int) (p >> 32); }
    static int uy(long p) { return (int) (p & 0xffffffffL); }

    @Override public void create() {
        gameCam = new OrthographicCamera();
        uiCam = new OrthographicCamera();
        sr = new ShapeRenderer();
        batch = new SpriteBatch();
        font = new BitmapFont(); font.getData().setScale(1.8f);
        fontBig = new BitmapFont(); fontBig.getData().setScale(3.5f);

        Gdx.input.setInputProcessor(new InputAdapter() {
            @Override public boolean touchDown(int x, int y, int pointer, int button) { return onDown(x, y, pointer); }
            @Override public boolean touchDragged(int x, int y, int pointer) { return onDrag(x, y, pointer); }
            @Override public boolean touchUp(int x, int y, int pointer, int button) { return onUp(pointer); }
        });
        startLevel(1);
    }

    @Override public void resize(int w, int h) {
        screenW = w; screenH = h;
        gameCam.setToOrtho(false, w, h);
        uiCam.setToOrtho(false, w, h);

        // UI элементы: D-pad слева внизу, кнопки справа внизу
        float pad = Math.min(w, h) * 0.04f;
        dpadR = Math.min(w, h) * 0.14f;
        btnR = dpadR * 0.42f;
        dpadCx = pad + dpadR + btnR;
        dpadCy = pad + dpadR + btnR;

        hideR = Math.min(w, h) * 0.065f;
        hideCx = w - pad - hideR;
        hideCy = pad + hideR;

        runR = Math.min(w, h) * 0.065f;
        runCx = w - pad - runR;
        runCy = hideCy + hideR + pad + runR;
    }

    // ===== Уровни =====
    void startLevel(int n) {
        level = n;
        keysNeeded  = Math.min(2 + level / 2, 7);
        monCount    = level <= 3 ? 1 : (level <= 7 ? 2 : 3);
        monInterval = Math.max(0.14f, 0.48f - level * 0.03f);
        detectRange = 5 + level / 2;
        batDrain    = 1.4f + level * 0.1f;
        flashBase   = Math.max(100f, 180f - level * 8f);
        batCount    = Math.max(3, 6 - level / 3);
        lockCount   = Math.max(2, 5 - level / 3);

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
            int[] nn = ns.get(rnd.nextInt(ns.size()));
            wall[(c[1] + nn[1]) / 2][(c[0] + nn[0]) / 2] = false;
            wall[nn[1]][nn[0]] = false;
            st.push(nn);
        }
        float loopP = Math.max(0.04f, 0.16f - level * 0.01f);
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
        placeN(floors, used, keys, keysNeeded);
        placeN(floors, used, batteries, batCount);
        placeN(floors, used, lockers, lockCount);
        for (long p : floors) {
            if (monsters.size() >= monCount) break;
            if (used.contains(p) || dist[uy(p)][ux(p)] < 10) continue;
            monsters.add(new int[]{ux(p), uy(p)}); used.add(p);
        }
        for (long p : floors) {
            if (monsters.size() >= monCount) break;
            if (used.contains(p)) continue;
            monsters.add(new int[]{ux(p), uy(p)}); used.add(p);
        }
    }

    void placeN(List<Long> floors, Set<Long> used, Set<Long> target, int n) {
        for (long p : floors) { if (target.size() >= n) break; if (used.contains(p)) continue; target.add(p); used.add(p); }
    }

    void say(String s) { msg = s; msgTimer = 2.2f; }
    boolean isWall(int x, int y) { if (x < 0 || y < 0 || x >= COLS || y >= ROWS) return true; return wall[y][x]; }
    void vibrate(int ms) { try { Gdx.input.vibrate(ms); } catch (Throwable ignored) {} }
    int nearest() { int b = 999; for (int[] m : monsters) b = Math.min(b, Math.abs(px - m[0]) + Math.abs(py - m[1])); return b; }

    void die() {
        if (dead) return;
        dead = true; scareTimer = 0; vibrated = false; shake = 35;
        heldDir = -1; runHeld = false;
    }

    // ===== Кадр =====
    @Override public void render() {
        float dt = Math.min(Gdx.graphics.getDeltaTime(), 0.05f);
        update(dt);
        Gdx.gl.glClearColor(0, 0, 0, 1);
        Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT);
        drawGame();
        drawUI();
    }

    void update(float dt) {
        time += dt;
        if (msgTimer > 0) msgTimer -= dt;
        if (shake > 0) shake = Math.max(0, shake - dt * 24);

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
            shake = Math.max(shake, (4 - nd) * 2f);
            vibeTimer += dt;
            float pulse = nd <= 1 ? 0.25f : nd <= 2 ? 0.42f : 0.65f;
            if (vibeTimer >= pulse) { vibeTimer = 0; vibrate(60); }
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
        if (batteries.remove(p)) { battery = Math.min(100, battery + 40); say("BATTERY +40%"); }
        if (px == doorX && py == doorY) {
            if (keysGot >= keysNeeded) { if (level >= MAX_LEVEL) won = true; else { clearTimer = 1.5f; say("LEVEL CLEARED"); } }
            else say("LOCKED " + keysGot + "/" + keysNeeded);
        }
        for (int[] m : monsters) if (m[0] == px && m[1] == py && !hidden) { die(); return; }
    }

    void toggleHide() {
        if (dead || won || clearTimer > 0) return;
        if (lockers.contains(pack(px, py))) {
            hidden = !hidden;
            if (!hidden) for (int[] m : monsters) if (m[0] == px && m[1] == py) { die(); return; }
            heldDir = -1; runHeld = false;
            say(hidden ? "HIDDEN" : "OUT");
        } else say("NO LOCKER");
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

    // ===== Отрисовка мира =====
    void drawGame() {
        // камера следит за игроком
        float cx = px * TILE + TILE / 2f;
        float cy = (ROWS - 1 - py) * TILE + TILE / 2f;  // Y перевёрнут для OpenGL
        gameCam.position.set(cx, cy, 0);
        // зажимаем камеру чтоб не вылезала за карту
        float hw = screenW / 2f, hh = screenH / 2f;
        gameCam.position.x = MathUtils.clamp(gameCam.position.x, hw, MAP_W - hw);
        gameCam.position.y = MathUtils.clamp(gameCam.position.y, hh, MAP_H - hh);
        gameCam.update();

        float ox = shake > 0 ? (rnd.nextFloat() * 2 - 1) * shake : 0;
        float oy = shake > 0 ? (rnd.nextFloat() * 2 - 1) * shake : 0;
        Matrix4 shaken = tmp.set(gameCam.combined).translate(ox, oy, 0);
        sr.setProjectionMatrix(shaken);
        Gdx.gl.glEnable(GL20.GL_BLEND);

        sr.begin(ShapeRenderer.ShapeType.Filled);
        // рисуем только видимые тайлы (оптимизация)
        int minTx = Math.max(0, (int)((gameCam.position.x - hw - 50) / TILE));
        int maxTx = Math.min(COLS - 1, (int)((gameCam.position.x + hw + 50) / TILE));
        int minTy = Math.max(0, (int)((gameCam.position.y - hh - 50) / TILE));
        int maxTy = Math.min(ROWS - 1, (int)((gameCam.position.y + hh + 50) / TILE));

        for (int gy = minTy; gy <= maxTy; gy++) {
            int my = ROWS - 1 - gy; // map y (перевёрнут)
            if (my < 0 || my >= ROWS) continue;
            for (int gx = minTx; gx <= maxTx; gx++) {
                float sx = gx * TILE, sy = gy * TILE;
                if (wall[my][gx]) { c(30, 30, 38, 1); sr.rect(sx, sy, TILE, TILE); c(48, 48, 60, 1); sr.rect(sx + 2, sy + 2, TILE - 4, TILE - 4); c(30, 30, 38, 1); sr.rect(sx + 4, sy + 4, TILE - 8, TILE - 8); }
                else { c(16, 16, 20, 1); sr.rect(sx, sy, TILE, TILE); }
            }
        }

        // предметы
        for (long p : lockers) drawAt(ux(p), uy(p), 80, 55, 30, true);
        if (keysGot >= keysNeeded) c(50, 220, 90, 1); else c(140, 35, 35, 1);
        float ddx = doorX * TILE + 6, ddy = (ROWS - 1 - doorY) * TILE + 6;
        sr.rect(ddx, ddy, TILE - 12, TILE - 12);

        c(255, 215, 50, 1);
        for (long p : keys) { float kx = ux(p) * TILE + TILE / 2f, ky = (ROWS - 1 - uy(p)) * TILE + TILE / 2f; sr.circle(kx, ky, TILE * 0.2f); }
        c(70, 220, 120, 1);
        for (long p : batteries) { float bx = ux(p) * TILE + TILE * 0.3f, by = (ROWS - 1 - uy(p)) * TILE + TILE * 0.3f; sr.rect(bx, by, TILE * 0.4f, TILE * 0.4f); }

        // монстры
        for (int[] m : monsters) {
            float mmx = m[0] * TILE + TILE / 2f, mmy = (ROWS - 1 - m[1]) * TILE + TILE / 2f;
            c(180, 15, 15, 1); sr.circle(mmx, mmy, TILE * 0.38f);
            c(255, 235, 0, 1);
            sr.circle(mmx - TILE * 0.12f, mmy + TILE * 0.06f, TILE * 0.08f);
            sr.circle(mmx + TILE * 0.12f, mmy + TILE * 0.06f, TILE * 0.08f);
        }

        // игрок
        float ppx = px * TILE + TILE / 2f, ppy = (ROWS - 1 - py) * TILE + TILE / 2f;
        if (hidden) c(50, 50, 50, 1); else c(100, 190, 255, 1);
        sr.circle(ppx, ppy, TILE * 0.28f);
        if (!hidden) { c(200, 230, 255, 1); sr.circle(ppx, ppy, TILE * 0.18f); }
        sr.end();

        // фонарь (затемнение)
        sr.begin(ShapeRenderer.ShapeType.Filled);
        float lr = hidden ? 80 : (flashBase + (float) battery * 2f + flicker); if (lr < 60) lr = 60;
        float inner = lr * 0.5f;
        for (int gy = minTy; gy <= maxTy; gy++) {
            int mpy = ROWS - 1 - gy;
            if (mpy < 0 || mpy >= ROWS) continue;
            for (int gx = minTx; gx <= maxTx; gx++) {
                float tcx = gx * TILE + TILE / 2f, tcy = gy * TILE + TILE / 2f;
                float dx = tcx - ppx, dy = tcy - ppy;
                float d = (float) Math.sqrt(dx * dx + dy * dy), a;
                if (d <= inner) a = 0; else if (d >= lr) a = 1; else a = (d - inner) / (lr - inner);
                if (a > 0) { sr.setColor(0, 0, 0, a); sr.rect(gx * TILE, gy * TILE, TILE, TILE); }
            }
        }
        // красный экран рядом с монстром
        int nd = nearest();
        if (!hidden && nd <= 4 && !dead) {
            float ra = (5 - nd) * 0.06f;
            sr.setColor(0.7f, 0, 0, ra);
            sr.rect(gameCam.position.x - hw, gameCam.position.y - hh, screenW, screenH);
        }
        sr.end();

        // скример
        if (dead) {
            sr.begin(ShapeRenderer.ShapeType.Filled);
            drawJumpscare(gameCam.position.x, gameCam.position.y);
            sr.end();
        }

        Gdx.gl.glDisable(GL20.GL_BLEND);
    }

    void drawAt(int mx, int my, int r, int g, int b, boolean rect) {
        c(r, g, b, 1);
        float ax = mx * TILE + 6, ay = (ROWS - 1 - my) * TILE + 6;
        sr.rect(ax, ay, TILE - 12, TILE - 12);
    }

    void drawJumpscare(float cx, float cy) {
        float t = scareTimer;
        float grow = Math.min(1f, t / 0.3f);
        float strobe = 0.5f + 0.4f * (float) Math.abs(Math.sin(t * 30));
        sr.setColor(0.6f, 0f, 0f, strobe);
        sr.rect(cx - screenW / 2, cy - screenH / 2, screenW, screenH);
        sr.setColor(0, 0, 0, 0.3f);
        sr.rect(cx - screenW / 2, cy - screenH / 2, screenW, screenH);

        float s = grow * (1f + 0.05f * (float) Math.sin(t * 24));
        float head = Math.min(screenW, screenH) * 0.32f * s;

        sr.setColor(0.06f, 0.02f, 0.02f, 1); sr.circle(cx, cy, head);
        sr.setColor(0.18f, 0.04f, 0.04f, 1); sr.circle(cx, cy, head * 0.9f);

        float ex = head * 0.4f, ey = head * 0.2f, er = head * 0.24f;
        sr.setColor(1f, 0.9f, 0.15f, 1);
        sr.circle(cx - ex, cy + ey, er); sr.circle(cx + ex, cy + ey, er);
        sr.setColor(0.95f, 0.08f, 0.03f, 1);
        sr.circle(cx - ex, cy + ey, er * 0.55f); sr.circle(cx + ex, cy + ey, er * 0.55f);
        sr.setColor(0, 0, 0, 1);
        sr.circle(cx - ex, cy + ey, er * 0.22f); sr.circle(cx + ex, cy + ey, er * 0.22f);

        sr.setColor(0, 0, 0, 1);
        sr.triangle(cx - head * 0.68f, cy + head * 0.58f, cx - head * 0.1f, cy + head * 0.35f, cx - head * 0.64f, cy + head * 0.32f);
        sr.triangle(cx + head * 0.68f, cy + head * 0.58f, cx + head * 0.1f, cy + head * 0.35f, cx + head * 0.64f, cy + head * 0.32f);

        float mw = head * 0.8f, mtop = cy - head * 0.18f, mbot = cy - head * 0.65f;
        sr.setColor(0, 0, 0, 1);
        sr.rect(cx - mw / 2, mbot, mw, mtop - mbot);
        sr.setColor(0.93f, 0.93f, 0.86f, 1);
        int teeth = 8;
        float tw = mw / teeth;
        for (int i = 0; i < teeth; i++) {
            float lx = cx - mw / 2 + i * tw;
            sr.triangle(lx, mtop, lx + tw, mtop, lx + tw / 2, mtop - head * 0.2f);
            sr.triangle(lx, mbot, lx + tw, mbot, lx + tw / 2, mbot + head * 0.2f);
        }
    }

    // ===== UI поверх игры (полупрозрачный) =====
    void drawUI() {
        uiCam.update();
        Gdx.gl.glEnable(GL20.GL_BLEND);
        sr.setProjectionMatrix(uiCam.combined);

        sr.begin(ShapeRenderer.ShapeType.Filled);

        // D-pad фон
        sr.setColor(1, 1, 1, 0.08f);
        sr.circle(dpadCx, dpadCy, dpadR + btnR);

        // стрелки
        float[][] dirs = {
            {dpadCx, dpadCy + dpadR},          // up
            {dpadCx, dpadCy - dpadR},          // down
            {dpadCx - dpadR, dpadCy},          // left
            {dpadCx + dpadR, dpadCy}           // right
        };
        for (int i = 0; i < 4; i++) {
            sr.setColor(1, 1, 1, heldDir == i ? 0.35f : 0.18f);
            sr.circle(dirs[i][0], dirs[i][1], btnR);
        }

        // HIDE кнопка
        sr.setColor(1, 0.3f, 0.3f, hidden ? 0.4f : 0.18f);
        sr.circle(hideCx, hideCy, hideR);

        // RUN кнопка
        sr.setColor(0.3f, 0.6f, 1, runHeld && stamina > 0 ? 0.4f : 0.18f);
        sr.circle(runCx, runCy, runR);

        // HUD: батарея (верх справа)
        float barW = screenW * 0.22f, barH = screenH * 0.015f;
        float barX = screenW - barW - 16, barY = screenH - 20;
        sr.setColor(1, 1, 1, 0.12f); sr.rect(barX, barY, barW, barH);
        if (battery > 40) sr.setColor(0.27f, 0.82f, 0.47f, 0.7f);
        else if (battery > 15) sr.setColor(0.9f, 0.78f, 0.24f, 0.7f);
        else sr.setColor(0.86f, 0.24f, 0.24f, 0.7f);
        sr.rect(barX, barY, (float)(barW * battery / 100), barH);

        // HUD: выносливость
        float stY = barY - barH - 6;
        sr.setColor(1, 1, 1, 0.12f); sr.rect(barX, stY, barW, barH);
        sr.setColor(0.35f, 0.6f, 0.9f, 0.7f); sr.rect(barX, stY, barW * stamina / 100f, barH);

        sr.end();

        // текст UI
        batch.setProjectionMatrix(uiCam.combined);
        batch.begin();

        font.setColor(1, 0.82f, 0.24f, 0.9f);
        font.draw(batch, "LVL " + level, 16, screenH - 10);
        font.draw(batch, "KEYS " + keysGot + "/" + keysNeeded, 16, screenH - 36);

        font.setColor(1, 1, 1, 0.6f);
        font.draw(batch, "LIGHT", barX, barY + barH + 18);
        font.draw(batch, "RUN", barX, stY + barH + 18);

        // кнопки — текст
        font.setColor(1, 1, 1, 0.7f);
        font.draw(batch, "^", dirs[0][0] - 8, dirs[0][1] + 12);
        font.draw(batch, "v", dirs[1][0] - 8, dirs[1][1] + 12);
        font.draw(batch, "<", dirs[2][0] - 8, dirs[2][1] + 12);
        font.draw(batch, ">", dirs[3][0] - 8, dirs[3][1] + 12);
        font.setColor(1, 0.4f, 0.4f, 0.8f);
        font.draw(batch, "H", hideCx - 10, hideCy + 12);
        font.setColor(0.4f, 0.7f, 1, 0.8f);
        font.draw(batch, "R", runCx - 8, runCy + 12);

        // сообщение
        if (msgTimer > 0) { font.setColor(1, 0.4f, 0.4f, 0.9f); font.draw(batch, msg, 16, screenH * 0.5f); }

        if (clearTimer > 0) {
            fontBig.setColor(0.27f, 0.86f, 0.47f, 1);
            fontBig.draw(batch, "LEVEL " + level + " CLEAR", screenW * 0.15f, screenH * 0.55f);
        }
        if (dead) {
            fontBig.setColor(1, 0.08f, 0.08f, 1);
            fontBig.draw(batch, "YOU DIED", screenW * 0.2f, screenH * 0.65f);
            font.setColor(1, 1, 1, 1);
            font.draw(batch, "tap to retry", screenW * 0.35f, screenH * 0.38f);
        }
        if (won) {
            fontBig.setColor(0.27f, 0.86f, 0.47f, 1);
            fontBig.draw(batch, "ESCAPED!", screenW * 0.2f, screenH * 0.6f);
            font.setColor(1, 1, 1, 1);
            font.draw(batch, "tap to restart", screenW * 0.32f, screenH * 0.4f);
        }
        batch.end();
        Gdx.gl.glDisable(GL20.GL_BLEND);
    }

    void c(int r, int g, int b, float a) { sr.setColor(r / 255f, g / 255f, b / 255f, a); }

    float dist(float x1, float y1, float x2, float y2) { float dx = x1 - x2, dy = y1 - y2; return (float) Math.sqrt(dx * dx + dy * dy); }

    int dirFromDpad(float x, float y) {
        float dx = x - dpadCx, dy = y - dpadCy;
        if (dx * dx + dy * dy > (dpadR + btnR + 20) * (dpadR + btnR + 20)) return -1;
        if (Math.abs(dx) > Math.abs(dy)) return dx > 0 ? 3 : 2;  // right : left
        else return dy > 0 ? 0 : 1;  // up : down (screen y inverted)
    }

    boolean onDown(int sx, int sy, int pointer) {
        float x = sx, y = screenH - sy;  // screen to UI coords
        if (dead || won) { startLevel(dead ? level : 1); return true; }
        if (clearTimer > 0) return true;

        if (dist(x, y, hideCx, hideCy) <= hideR * 1.4f) { toggleHide(); return true; }
        if (dist(x, y, runCx, runCy) <= runR * 1.4f) { runHeld = true; runPointer = pointer; return true; }

        int dir = dirFromDpad(x, y);
        if (dir >= 0) { heldDir = dir; heldPointer = pointer; moveAcc = 0; tryMove(dir); return true; }
        return true;
    }

    boolean onDrag(int sx, int sy, int pointer) {
        if (pointer == heldPointer) {
            float x = sx, y = screenH - sy;
            int dir = dirFromDpad(x, y);
            if (dir >= 0 && dir != heldDir) { heldDir = dir; moveAcc = 0; tryMove(dir); }
        }
        return true;
    }

    boolean onUp(int pointer) {
        if (pointer == heldPointer) { heldDir = -1; heldPointer = -1; }
        if (pointer == runPointer) { runHeld = false; runPointer = -1; }
        return true;
    }

    @Override public void dispose() { sr.dispose(); batch.dispose(); font.dispose(); fontBig.dispose(); }
}
