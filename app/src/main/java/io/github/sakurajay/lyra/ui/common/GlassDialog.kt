package io.github.sakurajay.lyra.ui.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.BasicAlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.sakurajay.lyra.ui.theme.GlassSurface

/** 普通 AlertDialog 的玻璃版本：形状、填充与更新弹窗一致。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GlassDialog(
    onDismissRequest: () -> Unit,
    title: String,
    content: @Composable ColumnScope.() -> Unit,
    buttons: @Composable RowScope.() -> Unit,
) {
    BasicAlertDialog(onDismissRequest = onDismissRequest) {
        GlassSurface(shape = RoundedCornerShape(28.dp), strong = true) {
            Column(Modifier.fillMaxWidth().padding(20.dp)) {
                Text(text = title, style = MaterialTheme.typography.headlineSmall)
                Column(Modifier.fillMaxWidth().padding(top = 14.dp), content = content)
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
                    horizontalArrangement = Arrangement.End,
                    content = buttons,
                )
            }
        }
    }
}
