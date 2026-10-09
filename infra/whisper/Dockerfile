# risime-whisper: faster-whisper on CTranslate2 built from source with CUDA for the GB10 (sm_121,
# aarch64). Decision 071. PyPI's aarch64 ctranslate2 wheels are CPU-only, so we build it.
# Base: NVIDIA's vLLM container (NGC, arm64, CUDA 13.4, cuDNN 9, Python 3.12, ffmpeg). It is
# already on spark2 for risi-l1, so its layers are shared (no extra download or disk), it has the
# CUDA toolchain to build with, and it carries vLLM's Whisper serving as the fallback (decision 071).
ARG BASE=nvcr.io/nvidia/vllm:26.09-py3

FROM ${BASE} AS build
ARG CT2_VERSION=v4.8.2
WORKDIR /src
RUN git clone --depth 1 --branch ${CT2_VERSION} --recurse-submodules --shallow-submodules \
      https://github.com/OpenNMT/CTranslate2.git
WORKDIR /src/CTranslate2
# CMake's FindCUDA arch table predates sm_121: pass the gencode flags directly.
RUN sed -i 's|^  cuda_select_nvcc_arch_flags(ARCH_FLAGS ${CUDA_ARCH_LIST})|  set(ARCH_FLAGS "-gencode=arch=compute_121,code=sm_121")|' CMakeLists.txt \
 && grep -q 'compute_121' CMakeLists.txt
# WITH_CUDNN=OFF: CTranslate2's cuDNN Conv1D uses the legacy cuDNN API with fixed algorithms,
# which cuDNN 9.26 rejects on sm_121 (CUDNN_STATUS_NOT_SUPPORTED). Without cuDNN it uses its own
# im2col + cuBLAS GEMM Conv1D (Whisper has only two conv layers, so the cost is negligible).
RUN cmake -S . -B build -DCMAKE_BUILD_TYPE=Release -DWITH_MKL=OFF -DWITH_RUY=ON \
      -DWITH_CUDA=ON -DWITH_CUDNN=OFF -DCUDA_DYNAMIC_LOADING=ON -DOPENMP_RUNTIME=COMP \
      -DBUILD_CLI=OFF -DCMAKE_INSTALL_PREFIX=/opt/ct2 \
 && cmake --build build -j"$(nproc)" \
 && cmake --install build
RUN cd python && pip install --no-cache-dir "pybind11>=2.10" \
 && CTRANSLATE2_ROOT=/opt/ct2 python3 setup.py bdist_wheel --dist-dir /wheels

FROM ${BASE}
ARG FASTER_WHISPER_VERSION=1.2.1
COPY --from=build /opt/ct2/lib/ /opt/ct2/lib/
COPY --from=build /wheels/ /wheels/
# av is pinned: PyAV 16+ dropped av.open(metadata_errors=...), which faster-whisper 1.2.1 passes.
ENV LD_LIBRARY_PATH=/opt/ct2/lib:${LD_LIBRARY_PATH}
RUN pip install --no-cache-dir /wheels/ctranslate2-*.whl \
      "faster-whisper==${FASTER_WHISPER_VERSION}" "av==15.1.0" \
 && python3 -c "import ctranslate2, faster_whisper; print('ctranslate2', ctranslate2.__version__, 'faster-whisper', faster_whisper.__version__)"
COPY server.py /app/server.py
WORKDIR /app
USER 1000:1000
ENV PYTHONUNBUFFERED=1 PYTHONDONTWRITEBYTECODE=1 HF_HUB_OFFLINE=1 HOME=/tmp
EXPOSE 8000
ENTRYPOINT []
CMD ["python3", "/app/server.py"]
