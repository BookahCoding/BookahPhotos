package dev.bookah.photos.collections

import android.graphics.Bitmap
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.ImageLoader
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.size.Size
import coil3.toBitmap
import dev.bookah.photos.data.PeopleIndex
import dev.bookah.photos.data.Person
import dev.bookah.photos.design.PhotoTheme
import dev.bookah.photos.library.Previews
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/** How the cover face is cut out of the picture: a square around the face box, 1.6 times its size, moved inside the picture (never black edges). */
internal object FaceCrop {
    const val PADDING = 1.6f

    /** The preview size to ask Nextcloud for so the face has about [wantPx] pixels across: 256, 512, 768 or 1024. */
    fun previewSize(boxWidth: Float, boxHeight: Float, aspect: Float = 1.33f, wantPx: Int = 192): Int {
        // the preview is fitted into a square of this size: the longer side has this many pixels
        val faceFraction = max(boxWidth, boxHeight * min(aspect, 1f / aspect).coerceAtLeast(0.5f)).coerceAtLeast(0.02f)
        val need = ceil(wantPx / (faceFraction * PADDING)).toInt()
        return ((need + 255) / 256 * 256).coerceIn(256, 1024)
    }

    /** The crop square (left, top, side) in pixels of a picture [w] by [h]. */
    fun square(box: FloatArray, w: Int, h: Int): IntArray {
        val side = (max(box[2] * w, box[3] * h) * PADDING).toInt().coerceIn(8, min(w, h))
        val cx = (box[0] + box[2] / 2) * w; val cy = (box[1] + box[3] / 2) * h
        val left = (cx - side / 2f).toInt().coerceIn(0, w - side)
        val top = (cy - side / 2f).toInt().coerceIn(0, h - side)
        return intArrayOf(left, top, side)
    }
}

/** Face pictures kept in memory: limited by their size (32 MB), not by how many there are. */
private val faceCache = object : LruCache<Long, ImageBitmap>(32 * 1024 * 1024) {
    override fun sizeOf(key: Long, value: ImageBitmap): Int = value.width * value.height * 4
}

/** The face of a person as a circle. The picture is the same Nextcloud preview the viewer uses, cut around the face box on the phone. */
@Composable
fun PersonAvatar(person: Person, previews: Previews, loader: ImageLoader, modifier: Modifier = Modifier, outPx: Int = 192) =
    FaceImage(person.coverFaceId, person.coverAssetId, person.box, previews, loader, modifier, outPx, circle = true)

/** One face, cut out of its photo around the box (fractions x, y, width, height). A circle for avatars, rounded squares for the faces page. */
@Composable
fun FaceImage(faceId: Long, assetId: Long, box: FloatArray, previews: Previews, loader: ImageLoader, modifier: Modifier = Modifier, outPx: Int = 192, circle: Boolean = false) {
    val context = LocalContext.current
    val bitmap by produceState(faceCache.get(faceId), faceId) {
        if (value == null) {
            value = withContext(Dispatchers.IO) {
                runCatching {
                    val px = FaceCrop.previewSize(box[2], box[3])
                    val request = ImageRequest.Builder(context).data(previews.fitUrl(assetId, px)).size(Size(px, px)).allowHardware(false)
                        .memoryCachePolicy(CachePolicy.DISABLED).build()
                    val src = (loader.execute(request) as? SuccessResult)?.image?.toBitmap() ?: return@runCatching null
                    val sq = FaceCrop.square(box, src.width, src.height)
                    val out = Bitmap.createBitmap(src, sq[0], sq[1], sq[2], sq[2])
                    Bitmap.createScaledBitmap(out, outPx, outPx, true).asImageBitmap()
                }.getOrNull()
            }?.also { faceCache.put(faceId, it) }
        }
    }
    Box(modifier.aspectRatio(1f).clip(if (circle) CircleShape else androidx.compose.foundation.shape.RoundedCornerShape(12.dp)).background(PhotoTheme.colors.surface), contentAlignment = Alignment.Center) {
        bitmap?.let { Image(it, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
    }
}

/** A person for a row or grid: the face and, under it, the name (or "Not named") in one line. */
@Composable
fun PersonItem(person: Person, previews: Previews, loader: ImageLoader, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Column(modifier.clickable(role = Role.Button, onClick = onClick), horizontalAlignment = Alignment.CenterHorizontally) {
        PersonAvatar(person, previews, loader, Modifier.fillMaxWidth())
        Text(
            personTitle(person), Modifier.padding(top = 6.dp), maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center,
            style = MaterialTheme.typography.labelLarge, color = if (person.name.isNullOrBlank()) PhotoTheme.colors.textSecondary else PhotoTheme.colors.textPrimary,
        )
    }
}

@Composable
fun personTitle(person: Person): String = person.name?.takeIf { it.isNotBlank() } ?: stringResource(R.string.not_named)

/** The People page: every person as a face with a name, three in a row. */
@Composable
fun PeoplePage(people: PeopleIndex, previews: Previews, loader: ImageLoader, onPerson: (Person) -> Unit, onBack: () -> Unit, bottomPadding: Dp, modifier: Modifier = Modifier, onHidden: () -> Unit = {}, onWhoIsThis: () -> Unit = {}) {
    val top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    Box(modifier.fillMaxSize().background(PhotoTheme.colors.background)) {
        LazyVerticalGrid(
            GridCells.Fixed(3), Modifier.fillMaxSize().testTagCompat("people-grid"),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = top + 72.dp, bottom = bottomPadding),
            horizontalArrangement = Arrangement.spacedBy(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            if (people.unassigned.isNotEmpty()) item(span = { androidx.compose.foundation.lazy.grid.GridItemSpan(maxLineSpan) }) {
                Text(
                    stringResource(R.string.who_entry, people.unassigned.size), Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = onWhoIsThis).padding(vertical = 10.dp).testTag("who-link"),
                    style = MaterialTheme.typography.titleMedium, color = PhotoTheme.colors.accent,
                )
            }
            if (people.hiddenPeople.isNotEmpty()) item(span = { androidx.compose.foundation.lazy.grid.GridItemSpan(maxLineSpan) }) {
                Text(
                    stringResource(R.string.hidden_people) + " (" + people.hiddenPeople.size + ")", Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = onHidden).padding(vertical = 8.dp).testTag("hidden-link"),
                    style = MaterialTheme.typography.titleMedium, color = PhotoTheme.colors.accent,
                )
            }
            items(people.people, key = { it.id }) { p -> PersonItem(p, previews, loader, { onPerson(p) }, Modifier.testTagCompat("person-${p.id}")) }
        }
        PageHeader(stringResource(R.string.people), onBack, Modifier.align(Alignment.TopStart).padding(top = top))
    }
}

private fun Modifier.testTagCompat(tag: String): Modifier = this.testTag(tag)
