package app.carry.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

val Ink = Color(0xFF07111C)
val InkDeep = Color(0xFF03070E)
val Panel = Color(0xFF102033)
val Line = Color(0xFF2C4664)
val Sky = Color(0xFF5CE1FF)
val Gold = Color(0xFFF2C14E)
val Coral = Color(0xFFE50914)
val Mist = Color(0xFFF4F7FB)
val Dim = Color(0xFF9AABC0)
val Good = Color(0xFF7DFFB3)
private val ButtonInk = Color(0xFF07111C)

@Composable
fun CarryTheme(content: @Composable () -> Unit) {
    Box(Modifier.background(Ink)) { content() }
}

@Composable
fun Wordmark(size: TextUnit = 56.sp) {
    Row(verticalAlignment = Alignment.Bottom) {
        Text("CAR", color = Mist, fontWeight = FontWeight.Black, fontSize = size, letterSpacing = 1.sp)
        Text("RY", color = Sky, fontWeight = FontWeight.Black, fontSize = size, letterSpacing = 1.sp)
    }
}

@Composable
fun ActionButton(
    label: String,
    primary: Boolean = false,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(if (focused) 1.05f else 1f, label = "action")
    val shape = RoundedCornerShape(4.dp)
    val fill = if (primary || focused) Sky else Color(0xFF163044)
    val labelColor = if (primary || focused) ButtonInk else Color.White
    Box(
        modifier
            .scale(scale)
            .clip(shape)
            .background(fill)
            .onFocusChanged { focused = it.isFocused }
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            )
            .padding(horizontal = 22.dp, vertical = 10.dp),
    ) {
        Text(label, color = labelColor, fontWeight = FontWeight.Bold, fontSize = 16.sp)
    }
}

@Composable
fun FocusCard(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(16.dp),
    content: @Composable BoxScope.() -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(if (focused) 1.04f else 1f, label = "focus")
    Box(
        modifier
            .scale(scale)
            .clip(shape)
            .border(3.dp, if (focused) Sky else Color.Transparent, shape)
            .background(Panel)
            .onFocusChanged { focused = it.isFocused }
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            ),
        content = content,
    )
}

@Composable
fun Pill(label: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    FocusCard(onClick = onClick, modifier = modifier, shape = RoundedCornerShape(999.dp)) {
        Text(
            label,
            color = Mist,
            modifier = Modifier.padding(horizontal = 22.dp, vertical = 12.dp),
            fontSize = 18.sp,
        )
    }
}

@Composable
fun Field(
    label: String,
    value: String,
    secret: Boolean = false,
    onChange: (String) -> Unit,
) {
    val keyboard = LocalSoftwareKeyboardController.current
    val focus = LocalFocusManager.current
    var editing by remember { mutableStateOf(false) }
    val requester = remember { FocusRequester() }
    BackHandler(enabled = editing) {
        editing = false
        keyboard?.hide()
        focus.clearFocus()
    }
    if (!editing) {
        val shape = RoundedCornerShape(12.dp)
        var focused by remember { mutableStateOf(false) }
        Column(
            Modifier
                .padding(bottom = 10.dp)
                .fillMaxWidth()
                .clip(shape)
                .border(if (focused) 4.dp else 2.dp, if (focused) Sky else Line, shape)
                .background(Panel)
                .onFocusChanged { focused = it.isFocused }
                .clickable { editing = true }
                .padding(16.dp),
        ) {
            Text(label, color = Dim, fontSize = 12.sp)
            Text(
                when {
                    value.isBlank() -> "tap to type"
                    secret -> "••••••"
                    else -> value
                },
                color = Mist,
                fontFamily = FontFamily.Monospace,
                fontSize = 18.sp,
            )
        }
    } else {
        BasicTextField(
            value = value,
            onValueChange = { onChange(it.replace("\n", "").replace("\r", "")) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(
                imeAction = ImeAction.Done,
                keyboardType = if (secret) KeyboardType.Password else KeyboardType.Uri,
            ),
            textStyle = TextStyle(color = Mist, fontSize = 20.sp, fontFamily = FontFamily.Monospace),
            visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None,
            modifier = Modifier
                .padding(bottom = 8.dp)
                .fillMaxWidth()
                .background(Panel, RoundedCornerShape(12.dp))
                .padding(16.dp)
                .focusRequester(requester),
            decorationBox = { inner ->
                Column {
                    Text(label, color = Dim, fontSize = 12.sp)
                    inner()
                }
            },
        )
        LaunchedEffect(editing) {
            requester.requestFocus()
            keyboard?.show()
        }
        Pill("done, hide keyboard") {
            editing = false
            keyboard?.hide()
            focus.clearFocus()
        }
    }
}

@Composable
fun SearchBox(placeholder: String, value: String, onChange: (String) -> Unit) {
    BasicTextField(
        value = value,
        onValueChange = { onChange(it.replace("\n", "").replace("\r", "")) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        textStyle = TextStyle(color = Mist, fontSize = 18.sp, fontFamily = FontFamily.Monospace),
        modifier = Modifier
            .fillMaxWidth()
            .background(Panel, RoundedCornerShape(12.dp))
            .padding(16.dp),
        decorationBox = { inner ->
            Box {
                if (value.isEmpty()) Text(placeholder, color = Dim, fontSize = 18.sp)
                inner()
            }
        },
    )
}

@Composable
fun Notice(
    title: String,
    body: String,
    confirm: String,
    dismiss: String?,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val stay = remember { FocusRequester() }
    LaunchedEffect(title) { runCatching { stay.requestFocus() } }
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.72f))
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {},
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier
                .widthIn(max = 560.dp)
                .padding(24.dp)
                .background(Panel, RoundedCornerShape(16.dp))
                .padding(28.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(title, color = Mist, fontSize = 26.sp, fontWeight = FontWeight.Black)
            Text(body, color = Dim, fontSize = 16.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                if (dismiss != null) {
                    ActionButton(dismiss, primary = true, modifier = Modifier.focusRequester(stay), onClick = onDismiss)
                    ActionButton(confirm, onClick = onConfirm)
                } else {
                    ActionButton(confirm, primary = true, modifier = Modifier.focusRequester(stay), onClick = onConfirm)
                }
            }
        }
    }
}
