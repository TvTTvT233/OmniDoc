-- 用户模块数据库初始化脚本
-- 创建数据库
CREATE DATABASE IF NOT EXISTS `omnidoc` DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;

-- 使用数据库
USE `omnidoc`;

-- 创建用户表
CREATE TABLE IF NOT EXISTS `user` (
  `id` BIGINT(20) NOT NULL AUTO_INCREMENT COMMENT '用户ID',
  `username` VARCHAR(50) NOT NULL COMMENT '用户名',
  `password` VARCHAR(100) NOT NULL COMMENT '密码（加密存储）',
  `email` VARCHAR(100) NOT NULL COMMENT '邮箱',
  `phone` VARCHAR(20) NOT NULL COMMENT '手机号',
  `role` VARCHAR(20) NOT NULL DEFAULT 'USER' COMMENT '角色',
  `status` TINYINT(1) NOT NULL DEFAULT 1 COMMENT '状态（0:禁用, 1:启用）',
  `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_username` (`username`),
  UNIQUE KEY `uk_email` (`email`),
  UNIQUE KEY `uk_phone` (`phone`),
  KEY `idx_status` (`status`),
  KEY `idx_create_time` (`create_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户表';

-- 插入初始数据
INSERT INTO `user` (`username`, `password`, `email`, `phone`, `role`, `status`) VALUES
-- 管理员账号（密码：${OMNIDOC_ADMIN_PASSWORD}）
('admin', '${OMNIDOC_PASSWORD_HASH}', '${OMNIDOC_ADMIN_EMAIL}', '${OMNIDOC_ADMIN_PHONE}', 'ADMIN', 1),
-- 测试用户（密码：${OMNIDOC_USER_PASSWORD}）
('user', '${OMNIDOC_PASSWORD_HASH}', '${OMNIDOC_USER_EMAIL}', '${OMNIDOC_USER_PHONE}', 'USER', 1);

-- 说明：
-- 1. 密码使用BCrypt加密，示例密码为：${OMNIDOC_ADMIN_PASSWORD} 和 ${OMNIDOC_USER_PASSWORD}
-- 2. 已创建唯一索引确保用户名、邮箱、手机号不重复
-- 3. 已创建普通索引优化查询性能
-- 4. 已设置默认值和自动更新时间
-- 5. 已插入初始管理员和测试用户数据
