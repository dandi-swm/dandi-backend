package com.dandi.nyummy.meal.mapper

import com.dandi.nyummy.meal.dto.IconSummary
import com.dandi.nyummy.meal.entity.Icon

fun Icon.toIconSummary(): IconSummary = IconSummary(
    id = id,
    name = name,
)
