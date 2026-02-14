# Test Templates

Шаблоны для написания различных типов тестов.

## Unit Test Template

```kotlin
class MyUtilTest : BaseUnitTest() {

    private lateinit var util: MyUtil

    @Before
    override fun setUp() {
        super.setUp()
        util = MyUtil()
    }

    @Test
    fun `should do something when condition met`() = runTest {
        // Given
        val input = "test"

        // When
        val result = util.process(input)

        // Then
        assertThat(result).isTrue()
    }

    @Test
    fun `should throw exception when invalid input`() = runTest {
        // Given
        val input = ""

        // When & Then
        assertThrows<IllegalArgumentException> {
            util.process(input)
        }
    }
}
```

## Instrumentation Test Template

```kotlin
class MyIntegrationTest : BaseInstrumentedTest() {

    @Test
    fun testMediaStoreIntegration() {
        // Given
        val context = ApplicationProvider.getApplicationContext<Context>()
        val testFile = createTestImage(context)

        // When
        val result = MediaStoreUtil.saveImage(context, testFile)

        // Then
        assertThat(result).isNotNull()
        assertThat(result.uri).isNotNull()
    }
}
```

## E2E Test Template

```kotlin
class MyFeatureE2ETest : BaseE2ETest() {

    @Test
    fun testCompleteUserFlow() {
        // Given
        launchMainActivity()

        // When
        onView(withId(R.id.settings_button))
            .perform(click())

        onView(withId(R.id.enable_auto_compression))
            .perform(click())

        pressBack()

        // Then
        onView(withId(R.id.status_text))
            .check(matches(withText("Автоматическая компрессия включена")))
    }
}
```

## Testing Async Code

```kotlin
@Test
fun testAsyncOperation() = runTest {
    val deferred = async { util.doAsyncWork() }
    val result = deferred.await()
    assertThat(result).isNotNull()
}
```

## Testing with Mocks

```kotlin
@Test
fun testWithMock() = runTest {
    val mockRepository = mockk<Repository>()
    every { mockRepository.getData() } returns Result.success("data")

    val util = MyUtil(mockRepository)
    val result = util.process()

    verify { mockRepository.getData() }
    assertThat(result).isTrue()
}
```

## Testing Coroutines

```kotlin
@Test
fun testCoroutine() = runTest {
    val testDispatcher = StandardTestDispatcher(testScheduler)
    Dispatchers.setMain(testDispatcher)

    viewModel.loadData()
    advanceUntilIdle()

    assertThat(viewModel.state.value).isEqualTo(State.Loaded)
}
```
