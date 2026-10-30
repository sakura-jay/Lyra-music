package io.github.sakurajay.lyra.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp

/**
 * Release 说明只需要最小 Markdown：标题、列表、粗体、行内代码与链接。
 *
 * 不引 Markdown 库：GitHub 正文里真正会被用户读的只有这几类，交给一个完整解析器
 * 反而会带进表格、HTML、图片这类不该在更新弹窗里展开的内容。
 */
@Composable
fun MarkdownText(markdown: String, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    val linkStyle = remember(scheme.primary) {
        TextLinkStyles(
            style = SpanStyle(color = scheme.primary, textDecoration = TextDecoration.Underline),
        )
    }
    val lines = remember(markdown) { markdown.lines() }
    Column(modifier.fillMaxWidth()) {
        lines.forEach { raw ->
            val line = raw.trimEnd()
            when {
                line.isBlank() -> Spacer(Modifier.height(6.dp))
                line.startsWith("### ") -> MarkdownLine(
                    text = line.removePrefix("### "),
                    style = MaterialTheme.typography.titleSmall,
                    linkStyle = linkStyle,
                )
                line.startsWith("## ") -> MarkdownLine(
                    text = line.removePrefix("## "),
                    style = MaterialTheme.typography.titleMedium,
                    linkStyle = linkStyle,
                )
                line.startsWith("# ") -> MarkdownLine(
                    text = line.removePrefix("# "),
                    style = MaterialTheme.typography.titleLarge,
                    linkStyle = linkStyle,
                )
                line.startsWith("- ") || line.startsWith("* ") -> Row(Modifier.fillMaxWidth()) {
                    Text("•", style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.width(6.dp))
                    MarkdownLine(
                        text = line.drop(2),
                        style = MaterialTheme.typography.bodyMedium,
                        linkStyle = linkStyle,
                    )
                }
                else -> MarkdownLine(
                    text = line,
                    style = MaterialTheme.typography.bodyMedium,
                    linkStyle = linkStyle,
                )
            }
        }
    }
}

@Composable
private fun MarkdownLine(
    text: String,
    style: androidx.compose.ui.text.TextStyle,
    linkStyle: TextLinkStyles,
) {
    Text(
        text = inlineMarkdown(text, linkStyle),
        style = style,
        color = MaterialTheme.colorScheme.onSurface,
    )
}

private val INLINE = Regex("""\*\*(.+?)\*\*|\[([^\]]+)]\(([^)\s]+)\)|`([^`]+)`""")

private fun inlineMarkdown(text: String, linkStyle: TextLinkStyles): AnnotatedString = buildAnnotatedString {
    var cursor = 0
    INLINE.findAll(text).forEach { match ->
        if (match.range.first > cursor) append(text.substring(cursor, match.range.first))
        val bold = match.groups[1]?.value
        val linkLabel = match.groups[2]?.value
        val linkUrl = match.groups[3]?.value
        val code = match.groups[4]?.value
        when {
            bold != null -> withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(bold) }
            linkLabel != null && linkUrl != null -> withLink(
                LinkAnnotation.Url(linkUrl, linkStyle),
            ) { append(linkLabel) }
            code != null -> withStyle(
                SpanStyle(fontFamily = FontFamily.Monospace, color = Color.Unspecified),
            ) { append(code) }
        }
        cursor = match.range.last + 1
    }
    if (cursor < text.length) append(text.substring(cursor))
}
