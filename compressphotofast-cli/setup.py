from setuptools import setup, find_packages

with open("README.md", "r", encoding="utf-8") as fh:
    long_description = fh.read()

setup(
    name="compressphotofast-cli",
    version="1.0.0",
    author="CompressPhotoFast Team",
    author_email="",
    description="Cross-platform CLI tool for fast image compression",
    long_description=long_description,
    long_description_content_type="text/markdown",
    url="https://github.com/yourusername/compressphotofast-cli",
    package_dir={"": "."},
    packages=find_packages(where="."),
    classifiers=[
        "Development Status :: 4 - Beta",
        "Intended Audience :: End Users/Desktop",
        "Topic :: Multimedia :: Graphics",
        "License :: OSI Approved :: MIT License",
        "Programming Language :: Python :: 3",
        "Programming Language :: Python :: 3.10",
        "Programming Language :: Python :: 3.11",
        "Programming Language :: Python :: 3.12",
        "Operating System :: OS Independent",
    ],
    python_requires=">=3.10",
    install_requires=[
        "Pillow>=10.0.0",
        "piexif>=1.1.3",
        "click>=8.1.0",
        "tqdm>=4.65.0",
        "rich>=13.0.0",
    ],
    entry_points={
        "console_scripts": [
            "compressphotofast=src.cli:cli",
        ],
    },
)
