package com.taskflow.repository;

import com.taskflow.model.UserSecurity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface UserSecurityRepository extends JpaRepository<UserSecurity, Long> {

    /** One indexed lookup per request (FreshAuthorityFilter), found by username like the user itself. */
    @Query("select s from UserSecurity s, User u where u.id = s.userId and lower(u.username) = lower(:username)")
    Optional<UserSecurity> findByUsername(@Param("username") String username);
}
