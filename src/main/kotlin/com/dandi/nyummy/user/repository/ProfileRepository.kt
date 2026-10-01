package com.dandi.nyummy.user.repository

import com.dandi.nyummy.user.entity.Profile
import org.springframework.data.jpa.repository.JpaRepository

interface ProfileRepository : JpaRepository<Profile, Long> {

    fun getProfileByUserId(userId: Long): Profile?
}
