package com.dandi.nyummy.cat.mapper

import com.dandi.nyummy.cat.dto.CatResponse
import com.dandi.nyummy.cat.entity.Cat
import com.dandi.nyummy.cat.enum.CatWeight

fun Cat.toCatResponse(): CatResponse = CatResponse(
    id = this.id,
    name = this.name,
    weight = CatWeight.fromWeight(this.weight).name,
    love = this.love,
    exp = this.exp,
)
