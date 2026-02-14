# Примеры оптимизации: UI Performance

## Missing DiffUtil

**Before:**
```kotlin
class PhotoAdapter : RecyclerView.Adapter<PhotoViewHolder>() {
    fun updateItems(newItems: List<Photo>) {
        items = newItems
        notifyDataSetChanged()  // Inefficient!
    }
}
```

**After:**
```kotlin
class PhotoAdapter : RecyclerView.Adapter<PhotoViewHolder>() {
    fun updateItems(newItems: List<Photo>) {
        val diff = PhotoDiffCallback(items, newItems)
        val result = DiffUtil.calculateDiff(diff)
        items = newItems
        result.dispatchUpdatesTo(this)
    }
}

class PhotoDiffCallback(
    private val old: List<Photo>,
    private val new: List<Photo>
) : DiffUtil.Callback() {
    override fun getOldListSize() = old.size
    override fun getNewListSize() = new.size
    override fun areItemsTheSame(oldPos: Int, newPos: Int) =
        old[oldPos].id == new[newPos].id
    override fun areContentsTheSame(oldPos: Int, newPos: Int) =
        old[oldPos] == new[newPos]
}
```

**Почему:** DiffUtil анимирует только изменённые элементы вместо всей списки.

---

## Missing ViewHolder Pattern

**Before:**
```kotlin
class PhotoAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_photo, parent, false)
        return object : RecyclerView.ViewHolder(view) {}  // No ViewHolder!
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val photo = items[position]
        holder.itemView.findViewById<TextView>(R.id.name).text = photo.name
        holder.itemView.findViewById<TextView>(R.id.size).text = photo.size
        // findViewById every time!
    }
}
```

**After:**
```kotlin
class PhotoViewHolder(view: View) : RecyclerView.ViewHolder(view) {
    val name: TextView = view.findViewById(R.id.name)
    val size: TextView = view.findViewById(R.id.size)
    // Cache views
}

class PhotoAdapter : RecyclerView.Adapter<PhotoViewHolder>() {
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PhotoViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_photo, parent, false)
        return PhotoViewHolder(view)
    }

    override fun onBindViewHolder(holder: PhotoViewHolder, position: Int) {
        val photo = items[position]
        holder.name.text = photo.name  // No findViewById!
        holder.size.text = photo.size
    }
}
```

**Почему:** ViewHolder кэширует views, избегая дорогого findViewById.

---

## Deep Layout Nesting

**Before:**
```xml
<!-- 8+ levels of nesting! -->
<LinearLayout>
    <LinearLayout>
        <LinearLayout>
            <LinearLayout>
                <LinearLayout>
                    <LinearLayout>
                        <TextView />
                    </LinearLayout>
                </LinearLayout>
            </LinearLayout>
        </LinearLayout>
    </LinearLayout>
</LinearLayout>
```

**After:**
```xml
<!-- 2 levels with ConstraintLayout -->
<androidx.constraintlayout.widget.ConstraintLayout
    xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:app="http://schemas.android.com/apk/res-auto"
    android:layout_width="match_parent"
    android:layout_height="wrap_content">

    <TextView
        android:id="@+id/name"
        android:layout_width="0dp"
        android:layout_height="wrap_content"
        app:layout_constraintTop_toTopOf="parent"
        app:layout_constraintStart_toStartOf="parent"
        app:layout_constraintEnd_toEndOf="parent" />

</androidx.constraintlayout.widget.ConstraintLayout>
```

**Почему:** ConstraintLayout уплощает иерархию, ускоряя отрисовку.

---

## ViewBinding vs findViewById

**Before:**
```kotlin
class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val name = findViewById<TextView>(R.id.name)
        val size = findViewById<TextView>(R.id.size)
        val date = findViewById<TextView>(R.id.date)
        // findViewById is expensive and error-prone
    }
}
```

**After:**
```kotlin
class MainActivity : AppCompatActivity() {
    private val binding: ActivityMainBinding by lazy {
        ActivityMainBinding.inflate(layoutInflater)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(binding.root)

        binding.name.text = "Photo"
        binding.size.text = "2.5 MB"
        // No findViewById, type-safe
    }
}
```

**Почему:** ViewBinding генерирует type-safe binding, без findViewById overhead.

---

## Overdraw Issues

**Before:**
```xml
<!-- Parent has background -->
<LinearLayout
    android:background="@color/white">

    <!-- Child has background too - OVERDRAW! -->
    <TextView
        android:background="@color/gray"
        android:text="Photo" />

</LinearLayout>
```

**After:**
```xml
<!-- Remove child background -->
<LinearLayout
    android:background="@color/white">

    <TextView
        android:text="Photo"
        android:textColor="@color/black" />

</LinearLayout>
```

**Или:** используй `android:background="@null"` у ребенка если фон не нужен.

**Почему:** Двойной фон = двойная отрисовка (overdraw) = медленнее.

---

## RecyclerView ItemDecoration Instead of Margins

**Before:**
```xml
<!-- Each item has margin - RecyclerView can't optimize -->
<LinearLayout
    android:layout_marginTop="8dp"
    android:layout_marginBottom="8dp">

    <TextView />

</LinearLayout>
```

**After:**
```kotlin
class MarginItemDecoration(private val margin: Int) : RecyclerView.ItemDecoration() {
    override fun getItemOffsets(outRect: Rect, view: View, parent: RecyclerView, state: RecyclerView.State) {
        outRect.top = margin
        outRect.bottom = margin
    }
}

// Apply:
recyclerView.addItemDecoration(MarginItemDecoration(8.dpToPx()))
```

```xml
<!-- Layout without margins -->
<LinearLayout>

    <TextView />

</LinearLayout>
```

**Почему:** RecyclerView может оптимизировать отрисовку, когда нет margins в item layout.
