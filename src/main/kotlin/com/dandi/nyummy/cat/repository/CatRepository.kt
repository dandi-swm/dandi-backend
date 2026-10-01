package com.dandi.nyummy.cat.repository

import com.dandi.nyummy.cat.entity.Cat
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository

@Repository
interface CatRepository : JpaRepository<Cat, Long> {
    fun findByUserId(userId: Long): Cat?
}
