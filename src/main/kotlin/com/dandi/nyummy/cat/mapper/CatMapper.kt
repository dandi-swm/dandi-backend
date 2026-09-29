package com.dandi.nyummy.cat.mapper

import com.dandi.nyummy.cat.dto.CatResponse
import com.dandi.nyummy.cat.entity.Cat
import com.dandi.nyummy.cat.enum.CatWeight

fun Cat.toCatResponse(): CatResponse = CatResponse(
    id = this.id,
    weight = this.weight,
    weightDescription = CatWeight.fromWeight(this.weight).description,
)
