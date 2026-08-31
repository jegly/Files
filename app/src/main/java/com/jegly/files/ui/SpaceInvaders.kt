package com.jegly.files.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import kotlin.random.Random

/**
 * Three taps on the version row.
 *
 * Everything is in normalised 0..1 space and scaled to whatever the canvas turns out to be, so
 * there is no layout maths anywhere and it looks the same on any screen. The sprites are 1-bit
 * bitmaps drawn as squares — the cheapest way to look like the thing it is imitating.
 *
 * One frame loop, one mutable state object, and a counter the Canvas reads so it redraws: a
 * game loop is the one place where Compose's "recompose when state changes" is more ceremony
 * than a frame tick and a repaint.
 */
@Composable
fun SpaceInvaders(onDismiss: () -> Unit) {
    val game = remember { Invaders() }
    var frame by remember { mutableIntStateOf(0) }

    LaunchedEffect(Unit) {
        var last = withFrameNanos { it }
        while (true) {
            val now = withFrameNanos { it }
            // Clamped: a frame lost to a GC pause must not teleport the fleet through the player.
            val dt = ((now - last) / 1_000_000_000f).coerceIn(0f, 0.05f)
            last = now
            game.step(dt)
            frame++
        }
    }

    val ink = MaterialTheme.colorScheme.primary
    val player = MaterialTheme.colorScheme.tertiary
    val danger = MaterialTheme.colorScheme.error
    val dim = MaterialTheme.colorScheme.onSurfaceVariant

    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = MaterialTheme.shapes.large, tonalElevation = 4.dp) {
            Column(Modifier.padding(16.dp)) {
                Row(
                    Modifier.fillMaxWidth().padding(bottom = 10.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("INVADERS", style = MaterialTheme.typography.labelLarge, color = dim)
                    Text(
                        game.score.toString().padStart(4, '0'),
                        style = MaterialTheme.typography.labelLarge,
                        color = ink,
                    )
                }

                Canvas(
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(0.78f)
                        .pointerInput(Unit) {
                            detectHorizontalDragGestures { change, _ ->
                                game.aimAt(change.position.x / size.width)
                            }
                        }
                        .pointerInput(Unit) {
                            detectTapGestures { offset ->
                                // Tap to fire, and to start again once it is over. Moving the
                                // ship to the tap as well means one finger does everything.
                                game.aimAt(offset.x / size.width)
                                if (game.over) game.reset() else game.fire()
                            }
                        },
                ) {
                    // Read so the Canvas subscribes to the frame counter and repaints.
                    @Suppress("UNUSED_EXPRESSION") frame

                    val w = size.width
                    val h = size.height
                    fun px(x: Float, y: Float, cell: Float, color: Color) =
                        drawRect(color, Offset(x * w, y * h), Size(cell * w, cell * w))

                    fun sprite(
                        bitmap: List<String>,
                        left: Float,
                        top: Float,
                        cell: Float,
                        color: Color,
                    ) {
                        bitmap.forEachIndexed { row, line ->
                            line.forEachIndexed { col, c ->
                                if (c == 'X') {
                                    px(left + col * cell, top + row * cell * (w / h), cell, color)
                                }
                            }
                        }
                    }

                    game.aliens.forEach { alien ->
                        if (!alien.alive) return@forEach
                        sprite(
                            if (game.legFrame) ALIEN_A else ALIEN_B,
                            game.alienX(alien),
                            game.alienY(alien),
                            ALIEN_CELL,
                            ink,
                        )
                    }

                    if (!game.over) {
                        sprite(SHIP, game.shipX - SHIP_HALF, SHIP_TOP, ALIEN_CELL, player)
                    }

                    game.shots.forEach { shot ->
                        px(shot.x, shot.y, ALIEN_CELL * 0.8f, player)
                    }
                    game.bombs.forEach { bomb ->
                        px(bomb.x, bomb.y, ALIEN_CELL * 0.8f, danger)
                    }

                    // The ground line, which is also where the fleet landing ends it.
                    drawRect(
                        dim.copy(alpha = 0.4f),
                        Offset(0f, GROUND * h),
                        Size(w, 1.dp.toPx()),
                    )
                }

                Text(
                    when {
                        game.won -> "CLEARED — tap to play again"
                        game.over -> "GAME OVER — tap to play again"
                        else -> "drag to move · tap to fire"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = if (game.over) ink else dim,
                    modifier = Modifier.padding(top = 10.dp),
                )
            }
        }
    }
}

private val ALIEN_A = listOf(
    "..X..X..",
    ".XXXXXX.",
    "XX.XX.XX",
    "XXXXXXXX",
    "X.X..X.X",
)

private val ALIEN_B = listOf(
    "..X..X..",
    "X.XXXX.X",
    "XXX..XXX",
    "XXXXXXXX",
    ".X....X.",
)

private val SHIP = listOf(
    "...X...",
    ".XXXXX.",
    "XXXXXXX",
)

private const val ALIEN_CELL = 0.014f
private const val COLUMNS = 6
private const val ROWS = 3
private const val COL_SPACING = 0.145f
private const val ROW_SPACING = 0.12f
private const val ALIEN_WIDTH = ALIEN_CELL * 8
private const val SHIP_HALF = ALIEN_CELL * 3.5f
private const val SHIP_TOP = 0.9f
private const val GROUND = 0.955f

private class Alien(val col: Int, val row: Int, var alive: Boolean = true)

/**
 * The whole game. Plain mutable state driven by [step]; nothing in here knows about Compose.
 */
private class Invaders {

    val aliens = mutableListOf<Alien>()
    val shots = mutableListOf<Offset>()
    val bombs = mutableListOf<Offset>()

    var fleetX = 0f
    var fleetY = 0f
    private var direction = 1f
    private var bombClock = 0f
    private var legClock = 0f

    var legFrame = false
        private set

    /*
     * Snapshot state, unlike everything above it. The score and the game-over line are read by
     * composables outside the Canvas, and a plain field would leave them stale forever: the frame
     * counter only invalidates the drawing, not the layout around it. The positional fields stay
     * plain because they are read during the draw, which the counter already invalidates — making
     * them state as well would just mean a recomposition every frame for no visible difference.
     */
    var score by mutableIntStateOf(0)
        private set
    var over by mutableStateOf(false)
        private set
    var won by mutableStateOf(false)
        private set

    var shipX = 0.5f
        private set

    init { reset() }

    fun reset() {
        aliens.clear()
        for (row in 0 until ROWS) for (col in 0 until COLUMNS) aliens += Alien(col, row)
        shots.clear()
        bombs.clear()
        fleetX = 0f
        fleetY = 0f
        direction = 1f
        bombClock = 0f
        score = 0
        over = false
        won = false
        shipX = 0.5f
    }

    fun alienX(alien: Alien): Float = 0.06f + alien.col * COL_SPACING + fleetX
    fun alienY(alien: Alien): Float = 0.08f + alien.row * ROW_SPACING + fleetY

    fun aimAt(x: Float) {
        shipX = x.coerceIn(SHIP_HALF, 1f - SHIP_HALF)
    }

    /** One shot in the air at a time, the way the original enforced restraint. */
    fun fire() {
        if (over || shots.isNotEmpty()) return
        shots += Offset(shipX, SHIP_TOP - 0.02f)
    }

    fun step(dt: Float) {
        if (over) return

        val alive = aliens.count { it.alive }
        if (alive == 0) { over = true; won = true; return }

        // The fewer left, the faster they come — the original's one piece of drama, and it comes
        // free from dividing by the survivors.
        val speed = 0.05f + 0.16f * (1f - alive.toFloat() / aliens.size)
        fleetX += direction * speed * dt

        val rightmost = aliens.filter { it.alive }.maxOf { it.col }
        val leftmost = aliens.filter { it.alive }.minOf { it.col }
        val right = 0.06f + rightmost * COL_SPACING + fleetX + ALIEN_WIDTH
        val left = 0.06f + leftmost * COL_SPACING + fleetX
        if (right > 0.98f && direction > 0f) { direction = -1f; fleetY += 0.035f }
        if (left < 0.02f && direction < 0f) { direction = 1f; fleetY += 0.035f }

        legClock += dt
        if (legClock > 0.35f) { legClock = 0f; legFrame = !legFrame }

        // Landed. Nothing else needs to happen for this to be lost.
        if (aliens.filter { it.alive }.maxOf { alienY(it) } > SHIP_TOP - 0.03f) {
            over = true
            return
        }

        shots.replaceAllWith { it.copy(y = it.y - 0.9f * dt) }
        shots.removeAll { it.y < 0f }

        shots.firstOrNull()?.let { shot ->
            val hit = aliens.firstOrNull { alien ->
                alien.alive &&
                    shot.x > alienX(alien) && shot.x < alienX(alien) + ALIEN_WIDTH &&
                    shot.y > alienY(alien) && shot.y < alienY(alien) + ALIEN_WIDTH
            }
            if (hit != null) {
                hit.alive = false
                shots.clear()
                score += 10
            }
        }

        bombClock -= dt
        if (bombClock <= 0f) {
            bombClock = 0.6f + Random.nextFloat() * 0.9f
            // Only the front alien in a column can drop one, so hiding behind a cleared column
            // is a real tactic rather than a coincidence.
            aliens.filter { it.alive }
                .groupBy { it.col }
                .values
                .randomOrNull()
                ?.maxByOrNull { it.row }
                ?.let { bombs += Offset(alienX(it) + ALIEN_WIDTH / 2, alienY(it) + 0.02f) }
        }

        bombs.replaceAllWith { it.copy(y = it.y + 0.55f * dt) }
        bombs.removeAll { it.y > GROUND }

        if (bombs.any {
                it.y > SHIP_TOP && it.x > shipX - SHIP_HALF && it.x < shipX + SHIP_HALF
            }
        ) {
            over = true
        }
    }

    private fun MutableList<Offset>.replaceAllWith(transform: (Offset) -> Offset) {
        for (i in indices) this[i] = transform(this[i])
    }
}
