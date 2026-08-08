package com.aiworkbench.mapper;

import com.aiworkbench.entity.User;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Select;

public interface UserMapper extends BaseMapper<User> {

    @Select("select count(*) from users")
    long countTotal();
}
