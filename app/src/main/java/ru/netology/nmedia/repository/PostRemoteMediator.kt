package ru.netology.nmedia.repository

import androidx.paging.ExperimentalPagingApi
import androidx.paging.LoadType
import androidx.paging.PagingState
import androidx.paging.RemoteMediator
import androidx.room.withTransaction
import ru.netology.nmedia.api.ApiService
import ru.netology.nmedia.dao.PostDao
import ru.netology.nmedia.dao.PostRemoteKeyDao
import ru.netology.nmedia.db.AppDb
import ru.netology.nmedia.entity.PostEntity
import ru.netology.nmedia.entity.PostRemoteKeyEntity
import ru.netology.nmedia.error.ApiError
import java.lang.Long.max
import java.lang.Long.min

@OptIn(ExperimentalPagingApi::class)
class PostRemoteMediator(
    private val service: ApiService,
    private val postDao: PostDao,
    private val postRemoteKeyDao: PostRemoteKeyDao,
    private val appDb: AppDb
) : RemoteMediator<Int, PostEntity>() {

    override suspend fun load(
        loadType: LoadType,
        state: PagingState<Int, PostEntity>
    ): MediatorResult {
        try {
            val response = when (loadType) {
                LoadType.REFRESH -> {
                    val id = postRemoteKeyDao.max()
                    if (id == null) {
                        service.getLatest(state.config.initialLoadSize)
                    } else {
                        service.getAfter(id, state.config.pageSize)
                    }
                }
                LoadType.PREPEND -> {
                    val id = postRemoteKeyDao.max() ?: return MediatorResult.Success(
                        endOfPaginationReached = false
                    )
                    service.getAfter(id, state.config.pageSize)
                }
                LoadType.APPEND -> {
                    val id = postRemoteKeyDao.min() ?: return MediatorResult.Success(
                        endOfPaginationReached = false
                    )
                    service.getBefore(id, state.config.pageSize)
                }
            }

            if (!response.isSuccessful) {
                throw ApiError(response.code(), response.message())
            }
            val body = response.body() ?: throw ApiError(
                response.code(),
                response.message(),
            )

            val bodyMinId = body.last().id

            var maxIdKey = postRemoteKeyDao.max()
            if (maxIdKey == null) maxIdKey = 0

            var minIdKey = postRemoteKeyDao.min()
            if (minIdKey == null) minIdKey = body.last().id

            appDb.withTransaction {
                when (loadType) {
                    LoadType.REFRESH -> {
                        postRemoteKeyDao.insert(
                            listOf(
                                PostRemoteKeyEntity(
                                    type = PostRemoteKeyEntity.KeyType.AFTER,
                                    id = body.first().id
                                ),
                                PostRemoteKeyEntity(
                                    type = PostRemoteKeyEntity.KeyType.BEFORE,
                                    id = max(minIdKey, bodyMinId)
                                ),
                            )
                        )
                        postDao.insert(
                            body
                                .filter { it.id > maxIdKey}
                                .map(PostEntity.Companion::fromDto)
                        )
                    }
                    LoadType.PREPEND -> {
                        postRemoteKeyDao.insert(
                            listOf(
                                PostRemoteKeyEntity(
                                    type = PostRemoteKeyEntity.KeyType.AFTER,
                                    id = body.first().id
                                ),
                                PostRemoteKeyEntity(
                                    type = PostRemoteKeyEntity.KeyType.BEFORE,
                                    id = max(minIdKey, bodyMinId)
                                ),
                            )
                        )
                        postDao.insert(
                            body
                                .filter { it.id > maxIdKey}
                                .map(PostEntity.Companion::fromDto)
                        )
                    }
                    LoadType.APPEND -> {
                        postRemoteKeyDao.insert(
                            PostRemoteKeyEntity(
                                type = PostRemoteKeyEntity.KeyType.BEFORE,
                                id = min(body.last().id, minIdKey),
                            )
                        )
                        postDao.insert(
                            body
                                .map(PostEntity.Companion::fromDto)
                        )
                    }
                }
            }
            return MediatorResult.Success(body.isEmpty())

        } catch (e: Exception) {
            return MediatorResult.Error(e)
        }
    }
}