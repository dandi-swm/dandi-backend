package com.dandi.nyummy.cat.mapper

import com.dandi.nyummy.cat.dto.CatResponse
import com.dandi.nyummy.cat.dto.CreateCatRequest
import com.dandi.nyummy.cat.entity.Cat
import com.dandi.nyummy.cat.enum.CatWeight

fun CreateCatRequest.toCat(userId: Long): Cat = Cat(
    name = this.name,
    userId = userId,
)

/** @param weightName 요청 언어로 꺼낸 체형 이름 */
fun Cat.toCatResponse(weightName: String): CatResponse = CatResponse(
    id = this.id,
    name = this.name,
    weight = CatWeight.fromWeight(this.weight),
    weightName = weightName,
    love = this.love,
    exp = this.exp,
)
