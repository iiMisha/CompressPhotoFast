"""
File-based locking mechanism for thread-safe file operations.

Provides a context manager for acquiring exclusive locks on files
using lock files to prevent race conditions in multi-threaded environments.
"""

import os
import time
import errno
from typing import Optional


class FileLockTimeoutError(Exception):
    """Raised when unable to acquire file lock within timeout period."""
    pass


class FileLock:
    """
    File-based lock using .lock files.

    This class provides a context manager for acquiring exclusive locks
    on files to prevent race conditions when multiple threads/processes
    try to access the same file simultaneously.

    Usage:
        with FileLock("/path/to/file.jpg"):
            # Safe file operations here
            process_file("/path/to/file.jpg")
    """

    def __init__(self, file_path: str, timeout: float = 30.0, poll_interval: float = 0.1):
        """
        Initialize file lock.

        Args:
            file_path: Path to the file to lock
            timeout: Maximum time to wait for lock acquisition (seconds)
            poll_interval: Time between lock acquisition attempts (seconds)
        """
        self.file_path = file_path
        self.lock_path = f"{file_path}.lock"
        self.timeout = timeout
        self.poll_interval = poll_interval
        self._lock_file: Optional[int] = None

    def _acquire_lock(self) -> None:
        """
        Attempt to acquire the file lock.

        Uses os.open() with O_EXCL flag for atomic lock file creation.
        Retries with timeout if lock is already held.

        Raises:
            FileLockTimeoutError: If lock cannot be acquired within timeout
        """
        start_time = time.time()

        while True:
            try:
                # Try to create lock file exclusively (atomic operation)
                self._lock_file = os.open(
                    self.lock_path,
                    os.O_CREAT | os.O_EXCL | os.O_RDWR,
                    0o644
                )
                # Lock acquired successfully
                return

            except OSError as e:
                if e.errno != errno.EEXIST:
                    # Error other than "file exists"
                    raise FileLockTimeoutError(
                        f"Failed to create lock file: {e}"
                    )

                # Lock file exists - check if it's stale (very old)
                try:
                    lock_age = time.time() - os.path.getmtime(self.lock_path)
                    if lock_age > self.timeout:
                        # Lock is stale, remove it
                        os.remove(self.lock_path)
                        continue
                except OSError:
                    pass

                # Check timeout
                if time.time() - start_time >= self.timeout:
                    raise FileLockTimeoutError(
                        f"Could not acquire lock for '{self.file_path}' "
                        f"within {self.timeout} seconds"
                    )

                # Wait before retrying
                time.sleep(self.poll_interval)

    def _release_lock(self) -> None:
        """
        Release the file lock by removing the lock file.

        Handles cleanup even if errors occur during processing.
        """
        try:
            if self._lock_file is not None:
                os.close(self._lock_file)
                self._lock_file = None
        except OSError:
            pass

        try:
            if os.path.exists(self.lock_path):
                os.remove(self.lock_path)
        except OSError:
            pass

    def __enter__(self) -> "FileLock":
        """
        Acquire the lock when entering context.

        Returns:
            self: The FileLock instance
        """
        self._acquire_lock()
        return self

    def __exit__(self, exc_type, exc_val, exc_tb) -> None:
        """
        Release the lock when exiting context.

        Ensures lock is released even if exception occurs.

        Args:
            exc_type: Exception type if raised
            exc_val: Exception value if raised
            exc_tb: Exception traceback if raised
        """
        self._release_lock()
        return False  # Don't suppress exceptions
