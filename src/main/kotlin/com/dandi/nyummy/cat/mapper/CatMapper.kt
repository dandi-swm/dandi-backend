package com.dandi.nyummy.cat.mapper

import com.dandi.nyummy.cat.dto.CatResponse
import com.dandi.nyummy.cat.dto.CreateCatRequest
import com.dandi.nyummy.cat.entity.Cat
import com.dandi.nyummy.cat.enum.CatWeight

fun CreateCatRequest.toCat(userId: Long): Cat = Cat(
    name = this.name,
    userId = userId,
)

fun Cat.toCatResponse(): CatResponse = CatResponse(
    id = this.id,
    name = this.name,
    weight = CatWeight.fromWeight(this.weight).name,
    weightName = CatWeight.fromWeight(this.weight).description,
    love = this.love,
    exp = this.exp,
)
