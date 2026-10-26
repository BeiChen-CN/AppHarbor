package com.app.market.ui.util

import androidx.compose.runtime.Composable
import com.app.market.domain.model.market.AppSource
import com.app.market.resources.Res
import com.app.market.resources.source_fdroid
import com.app.market.resources.source_fdroid_tuna
import com.app.market.resources.source_izzyondroid
import com.app.market.resources.source_github
import com.app.market.resources.source_gitlab
import com.app.market.resources.source_lsposed
import com.app.market.resources.source_x360
import com.app.market.resources.source_shoudji
import com.app.market.resources.source_hais
import com.app.market.resources.source_tencent
import com.app.market.resources.source_box7723
import com.app.market.resources.source_appchina
import com.app.market.resources.source_coolapk
import com.app.market.resources.source_honor
import com.app.market.resources.source_huawei
import com.app.market.resources.source_kuaibao
import com.app.market.resources.source_oppo
import com.app.market.resources.source_samsung
import com.app.market.resources.source_taptap
import com.app.market.resources.source_vivo
import com.app.market.resources.source_wandoujia
import com.app.market.resources.source_xiaomi
import org.jetbrains.compose.resources.stringResource

@Composable
fun appSourceLabel(source: AppSource): String = when (source) {
    AppSource.XIAOMI -> stringResource(Res.string.source_xiaomi)
    AppSource.VIVO -> stringResource(Res.string.source_vivo)
    AppSource.WANDOUJIA -> stringResource(Res.string.source_wandoujia)
    AppSource.OPPO -> stringResource(Res.string.source_oppo)
    AppSource.SAMSUNG -> stringResource(Res.string.source_samsung)
    AppSource.HONOR -> stringResource(Res.string.source_honor)
    AppSource.HUAWEI -> stringResource(Res.string.source_huawei)
    AppSource.TAPTAP -> stringResource(Res.string.source_taptap)
    AppSource.KUAIBAO -> stringResource(Res.string.source_kuaibao)
    AppSource.FDROID -> stringResource(Res.string.source_fdroid)
    AppSource.FDROID_TUNA -> stringResource(Res.string.source_fdroid_tuna)
    AppSource.TENCENT -> stringResource(Res.string.source_tencent)
    AppSource.BOX7723 -> stringResource(Res.string.source_box7723)
    AppSource.APPCHINA -> stringResource(Res.string.source_appchina)
    AppSource.COOLAPK -> stringResource(Res.string.source_coolapk)
    AppSource.IZZYONDROID -> stringResource(Res.string.source_izzyondroid)
    AppSource.GITHUB -> stringResource(Res.string.source_github)
    AppSource.GITLAB -> stringResource(Res.string.source_gitlab)
    AppSource.LSPOSED -> stringResource(Res.string.source_lsposed)
    AppSource.X360 -> stringResource(Res.string.source_x360)
    AppSource.SHOUDJI -> stringResource(Res.string.source_shoudji)
    AppSource.HAIS -> stringResource(Res.string.source_hais)
}
