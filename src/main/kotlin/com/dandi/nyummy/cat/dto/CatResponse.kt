package com.dandi.nyummy.cat.dto

import com.dandi.nyummy.cat.enum.CatWeight

data class CatResponse(val id: Long, val weight: Int, val weightDescription: String)
