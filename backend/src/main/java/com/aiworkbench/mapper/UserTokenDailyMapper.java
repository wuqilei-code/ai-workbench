package com.aiworkbench.mapper;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDate;

/** 每用户每日 token 用量(配额校验)。 */
@Mapper
public interface UserTokenDailyMapper {

    /** 当日累计 token(getToday 用;无记录返回 null)。 */
    @Select("SELECT tokens FROM user_token_daily WHERE user_id = #{userId} AND day = #{day}")
    Long selectTokens(@Param("userId") Long userId, @Param("day") LocalDate day);

    /** 幂等累加当日 token(不存在则插入)。 */
    @Insert("INSERT INTO user_token_daily (user_id, day, tokens) VALUES (#{userId}, #{day}, #{delta}) "
            + "ON DUPLICATE KEY UPDATE tokens = tokens + #{delta}")
    int addTokens(@Param("userId") Long userId, @Param("day") LocalDate day, @Param("delta") long delta);
}
