package com.evocode.mapper;

import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * knowledge_chunk 删除级联专用（该表读写归 analyzer；backend 仅项目删除时清库）。
 */
@Mapper
public interface KnowledgeChunkMapper {

    @Delete("DELETE FROM knowledge_chunk WHERE project_id = #{projectId}")
    int deleteByProjectId(@Param("projectId") Long projectId);
}
