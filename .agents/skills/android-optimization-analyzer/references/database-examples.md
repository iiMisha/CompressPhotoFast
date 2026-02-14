# Примеры оптимизации: Database

## N+1 Query Problem

**Before:**
```kotlin
@Dao
interface PhotoDao {
    @Query("SELECT * FROM albums")
    suspend fun getAlbums(): List<Album>

    @Query("SELECT * FROM photos WHERE albumId = :albumId")
    suspend fun getPhotosForAlbum(albumId: Long): List<Photo>
}

// Usage (N+1 queries):
suspend fun loadAlbumsWithPhotos(): List<AlbumWithPhotos> {
    return albumDao.getAlbums().map { album ->
        AlbumWithPhotos(album, photoDao.getPhotosForAlbum(album.id))
    }
}
```

**After:**
```kotlin
@Dao
interface AlbumDao {
    @Transaction
    @Query("SELECT * FROM albums")
    suspend fun getAlbumsWithPhotos(): List<AlbumWithPhotos>
}

@Transaction
data class AlbumWithPhotos(
    @Embedded val album: Album,
    @Relation(parentColumn = "id", entityColumn = "albumId")
    val photos: List<Photo>
)

// Usage (1 query):
suspend fun loadAlbumsWithPhotos() = albumDao.getAlbumsWithPhotos()
```

**Почему:** `@Relation` загружает все данные одним запросом вместо N+1.

---

## Missing Transaction

**Before:**
```kotlin
suspend fun updatePhoto(photo: Photo) {
    photoDao.update(photo)          // Query 1
    photoDao.updateThumbnail(photo) // Query 2
    // Not atomic!
}
```

**After:**
```kotlin
@Transaction
suspend fun updatePhoto(photo: Photo) {
    photoDao.update(photo)
    photoDao.updateThumbnail(photo)
    // Atomic transaction
}
```

**Почему:** `@Transaction` делает операции атомарными и быстрее (одна транзакция вместо двух).

---

## Missing Index

**Before:**
```kotlin
@Entity
data class Photo(
    @PrimaryKey val id: Long,
    val albumId: Long,  // No index!
    val name: String,
    val size: Long
)

@Query("SELECT * FROM photos WHERE albumId = :albumId")
suspend fun getPhotosForAlbum(albumId: Long): List<Photo>
// Full table scan!
```

**After:**
```kotlin
@Entity(
    indices = [Index(value = ["albumId"])]  // Added index
)
data class Photo(
    @PrimaryKey val id: Long,
    val albumId: Long,
    val name: String,
    val size: Long
)

@Query("SELECT * FROM photos WHERE albumId = :albumId")
suspend fun getPhotosForAlbum(albumId: Long): List<Photo>
// Index seek - fast!
```

**Почему:** Индекс на `albumId` позволяет БД быстро находить записи вместо полного скана таблицы.

---

## Inefficient Column Types

**Before:**
```kotlin
@Entity
data class Photo(
    @PrimaryKey val id: Long,
    val name: String,  // TEXT - inefficient for booleans
    val isCompressed: String,  // "true"/"false" as string!
    val size: Long
)
```

**After:**
```kotlin
@Entity
data class Photo(
    @PrimaryKey val id: Long,
    val name: String,
    val isCompressed: Boolean,  // INTEGER 0/1 - efficient
    val size: Long
)
```

**Почему:** Boolean (INTEGER 0/1) занимает 1 байт, String - переменная длина + оверхед.

---

## Large Objects in One Query

**Before:**
```kotlin
@Query("SELECT * FROM photos")
suspend fun getAllPhotos(): List<Photo>
// Loads everything including large thumbnails!
```

**After:**
```kotlin
@Query("SELECT id, name, size, isCompressed FROM photos")  // Exclude thumbnail
suspend fun getPhotoPreviews(): List<PhotoPreview>

@Query("SELECT * FROM photos WHERE id = :id")
suspend fun getPhotoById(id: Long): Photo?  // Load full photo only when needed
```

**Почему:** Загрузка только нужных колонок уменьшает память и время выполнения.

---

## Batching Operations

**Before:**
```kotlin
suspend fun insertPhotos(photos: List<Photo>) {
    photos.forEach { photo ->
        photoDao.insert(photo)  // N transactions!
    }
}
```

**After:**
```kotlin
@Transaction
suspend fun insertPhotos(photos: List<Photo>) {
    photoDao.insertAll(photos)  // 1 transaction
}

@Dao
interface PhotoDao {
    @Insert
    suspend fun insertAll(photos: List<Photo>)  // Room supports batching
}
```

**Почему:** Одна транзакция вместо N уменьшает время выполнения в разы.

---

## Uncollected Relations

**Before:**
```kotlin
@Query("SELECT * FROM photos p " +
        "JOIN albums a ON p.albumId = a.id " +
        "WHERE a.name = :albumName")
suspend fun getPhotosForAlbumName(albumName: String): List<Photo>
// Manual join - complex and error-prone
```

**After:**
```kotlin
@Transaction
@Query("SELECT * FROM albums WHERE name = :albumName")
suspend fun getAlbumByName(albumName: String): AlbumWithPhotos?
// Room automatically loads related photos via @Relation
```

**Почему:** `@Relation` автоматически генерирует правильный JOIN и загружает связанные данные.
