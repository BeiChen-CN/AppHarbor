package com.app.market.domain.exception

/** 无法核对评论来源的包名时停止加载，防止显示同名应用的评论。 */
class CommentSourceResolutionException : RuntimeException("Cannot verify comment source package")
