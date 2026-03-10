package org.vaachak.reader.leisure.ui.settings

import app.cash.turbine.test
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.vaachak.reader.core.data.local.ProfileDao
import org.vaachak.reader.core.data.repository.VaultRepository
import org.vaachak.reader.core.domain.model.ProfileEntity
import org.vaachak.reader.core.utils.SecurityUtils

@OptIn(ExperimentalCoroutinesApi::class)
class ProfileViewModelTest {

    // Dependencies
    private lateinit var profileDao: ProfileDao
    private lateinit var vaultRepository: VaultRepository
    private lateinit var viewModel: ProfileViewModel

    // Test Coroutine Dispatcher
    private val testDispatcher = StandardTestDispatcher()

    // Fake Flows to control what the ViewModel sees
    private val fakeProfilesFlow = MutableStateFlow<List<ProfileEntity>>(emptyList())
    private val fakeVaultIdFlow = MutableStateFlow<String?>("guest_vault")

    // Test Data
    private val dummyProfile = ProfileEntity(
        profileId = "profile_piyush",
        name = "Piyush",
        isGuest = false,
        pinHash = SecurityUtils.hashPin("1234"), // Simulating an encrypted PIN
        avatarColorHex = "#FFFFFF",
        lastActiveTimestamp = 1000L
    )

    private val unlockedProfile = ProfileEntity(
        profileId = "profile_guest",
        name = "Guest",
        isGuest = true,
        pinHash = null, // No PIN
        avatarColorHex = "#000000",
        lastActiveTimestamp = 2000L
    )

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)

        // 1. Setup the mocks
        profileDao = mockk(relaxed = true)
        vaultRepository = mockk(relaxed = true)

        // 2. Tie the mocks to our fake flows
        every { profileDao.getAllProfiles() } returns fakeProfilesFlow
        every { vaultRepository.activeVaultIdFlow } returns fakeVaultIdFlow

        // 3. Initialize the ViewModel
        viewModel = ProfileViewModel(profileDao, vaultRepository)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `profiles and activeProfile state flows initialize and combine correctly`() = runTest {
        // Given: The database has two profiles, and DataStore says Piyush is active
        fakeProfilesFlow.value = listOf(dummyProfile, unlockedProfile)
        fakeVaultIdFlow.value = "profile_piyush"

        // When/Then: We observe the active profile via Turbine
        viewModel.activeProfile.test {
            // Wait for coroutines to process the combine() operator
            val active = awaitItem()

            // Assert it successfully found Piyush
            assertEquals("Piyush", active?.name)
            assertEquals("profile_piyush", active?.profileId)
        }
    }

    @Test
    fun `createProfile hashes PIN and inserts into database`() = runTest {
        // When: User creates a profile with a PIN
        viewModel.createProfile(name = "NewUser", pin = "9999", isGuest = false)
        advanceUntilIdle() // Ensure coroutines finish

        // Then: The DAO should be called with a securely hashed PIN, not the raw text
        coVerify {
            profileDao.insertProfile(match {
                it.name == "NewUser" &&
                        it.pinHash == SecurityUtils.hashPin("9999") &&
                        it.pinHash != "9999" // Proving it didn't save plain text
            })
        }
    }

    @Test
    fun `unlockProfile with correct PIN succeeds and updates vault`() = runTest {
        // When: We try to unlock the profile with the correct PIN
        val isSuccess = viewModel.unlockProfile(dummyProfile, "1234")
        advanceUntilIdle()

        // Then: It returns true, clears any errors, and updates DataStore/Room
        assertTrue(isSuccess)
        assertNull(viewModel.pinError.value)
        coVerify { vaultRepository.setActiveVaultId("profile_piyush") }
        coVerify { profileDao.updateLastActive(eq("profile_piyush"), any()) }
    }

    @Test
    fun `unlockProfile with incorrect PIN fails and emits pinError state`() = runTest {
        // When: We use the wrong PIN
        val isSuccess = viewModel.unlockProfile(dummyProfile, "0000")

        // Then: It returns false and emits a UI error message
        assertFalse(isSuccess)
        assertEquals("Incorrect PIN", viewModel.pinError.value)

        // Ensure no security breach (DataStore wasn't changed)
        coVerify(exactly = 0) { vaultRepository.setActiveVaultId(any()) }
    }

    @Test
    fun `selectProfile activates profile instantly if no PIN is required`() = runTest {
        // Given: A callback listener
        var activatedId: String? = null
        val onActivated = { id: String -> activatedId = id }

        // When: User selects a profile that has NO pin (pinHash = null)
        viewModel.selectProfile(unlockedProfile, enteredPin = null, onActivated = onActivated)
        advanceUntilIdle()

        // Then: It bypasses security checks and activates instantly
        assertEquals("profile_guest", activatedId)
        coVerify { vaultRepository.setActiveVaultId("profile_guest") }
    }

    @Test
    fun `changePin fails if current PIN is missing or incorrect`() = runTest {
        // When: Try to change PIN without providing the old one
        val successNoOld = viewModel.changePin(dummyProfile, oldPin = "", newPin = "5678")
        assertFalse(successNoOld)
        assertEquals("Current PIN required", viewModel.pinError.value)

        // When: Try to change PIN with WRONG old one
        val successWrongOld = viewModel.changePin(dummyProfile, oldPin = "1111", newPin = "5678")
        assertFalse(successWrongOld)
        assertEquals("Incorrect current PIN", viewModel.pinError.value)

        // Ensure database update was never called
        coVerify(exactly = 0) { profileDao.updatePin(any(), any()) }
    }

    @Test
    fun `changePin succeeds with correct old PIN`() = runTest {
        // When: Try to change PIN with correct old PIN
        val success = viewModel.changePin(dummyProfile, oldPin = "1234", newPin = "5678")
        advanceUntilIdle()

        // Then: It returns true and updates the DB with the new hash
        assertTrue(success)
        assertNull(viewModel.pinError.value)
        coVerify { profileDao.updatePin("profile_piyush", SecurityUtils.hashPin("5678")) }
    }
}