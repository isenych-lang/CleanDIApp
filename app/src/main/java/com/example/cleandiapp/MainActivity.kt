package com.example.cleandiapp

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.room.*
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.GET
import javax.inject.Inject
import javax.inject.Singleton

// ================= 1. DOMAIN LAYER (Бизнес-логика, абстракции) =================
data class Product(val id: Int, val name: String, val price: Double)

interface ProductRepository {
    fun getProductsFromCache(): Flow<List<Product>>
    suspend fun refreshProductsFromNetwork()
}

class GetProductsUseCase @Inject constructor(private val repository: ProductRepository) {
    operator fun invoke(): Flow<List<Product>> = repository.getProductsFromCache()
}

class RefreshProductsUseCase @Inject constructor(private val repository: ProductRepository) {
    suspend operator fun invoke() = repository.refreshProductsFromNetwork()
}

// ================= 2. DATA LAYER (Сетевые DTO, БД Room, Реализация репозитория) =================
data class ProductDto(val id: Int, val title: String, val price: Double)

interface ApiService {
    @GET("products")
    suspend fun getProducts(): List<ProductDto> // Пример эндпоинта
}

@Entity(tableName = "cached_products")
data class ProductEntity(@PrimaryKey val id: Int, val name: String, val price: Double)

@Dao
interface ProductDao {
    @Query("SELECT * FROM cached_products")
    fun getAll(): Flow<List<ProductEntity>>
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(products: List<ProductEntity>)
}

@Database(entities = [ProductEntity::class], version = 1)
abstract class AppDatabase : RoomDatabase() {
    abstract fun productDao(): ProductDao
}

// Реализация репозитория (связывает сеть и локальный кэш Room)
class ProductRepositoryImpl @Inject constructor(
    private val apiService: ApiService,
    private val productDao: ProductDao
) : ProductRepository {

    override fun getProductsFromCache(): Flow<List<Product>> {
        return productDao.getAll().map { entities ->
            entities.map { Product(it.id, it.name, it.price) }
        }
    }

    override suspend fun refreshProductsFromNetwork() {
        try {
            val networkData = apiService.getProducts()
            val entities = networkData.map { ProductEntity(it.id, it.title, it.price) }
            productDao.insertAll(entities)
        } catch (e: Exception) {
            // Обработка ошибок сети
        }
    }
}

// ================= 3. DI LAYER (Dagger Hilt Настройки) =================
@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {
    @Provides
    @Singleton
    fun provideApiService(): ApiService {
        return Retrofit.Builder()
            .baseUrl("https://fakestoreapi.com/")
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(ApiService::class.java)
    }
}

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {
    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): AppDatabase {
        return Room.databaseBuilder(context, AppDatabase::class.java, "clean_db").build()
    }
    @Provides
    fun provideProductDao(db: AppDatabase): ProductDao = db.productDao()
}

@Module
@InstallIn(SingletonComponent::class)
abstract class RepositoryModule {
    @Binds
    @Singleton
    abstract fun bindRepository(impl: ProductRepositoryImpl): ProductRepository
}

// ================= 4. PRESENTATION LAYER (MVVM + UI) =================
@HiltViewModel
class ProductViewModel @Inject constructor(
    private val getProductsUseCase: GetProductsUseCase,
    private val refreshProductsUseCase: RefreshProductsUseCase
) : ViewModel() {

    val products: StateFlow<List<Product>> = getProductsUseCase()
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    init {
        viewModelScope.launch { refreshProductsUseCase() }
    }
}

@Composable
fun ProductListScreen(viewModel: ProductViewModel) {
    val products by viewModel.products.collectAsState()

    LazyColumn(Modifier.padding(16.dp)) {
        items(products) { product ->
            Card(Modifier.fillMaxWidth().padding(8.dp)) {
                Column(Modifier.padding(12.dp)) {
                    Text(product.name, style = MaterialTheme.typography.titleMedium)
                    Text("${product.price} ₴")
                }
            }
        }
    }
}

// ================= 5. ENTRY POINT =================
@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                val viewModel: ProductViewModel = dagger.hilt.android.lifecycle.HiltViewModel()
                ProductListScreen(viewModel)
            }
        }
    }
}
