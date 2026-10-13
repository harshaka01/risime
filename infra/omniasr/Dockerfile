# risime-omniasr: Meta Omnilingual ASR (omniASR, Apache-2.0) on the GB10 (aarch64, sm_121) for the
# ASR eval and the developer compare mode only (never the production route). 2026-10-14, Part 12.
# fairseq2n (fairseq2's C++ data library) has no linux-aarch64 wheel and pins torch==2.9.1, so it is
# built here from source against the base image's NVIDIA torch (2.14). The native part is CPU-only
# (audio decoding, collation, the tokenizer); the model itself runs on CUDA through torch.
# Base: NVIDIA's vLLM container (NGC, arm64, CUDA 13.4, Python 3.12), already on spark2 for
# risi-l1 and risime-whisper, so its layers are shared (no extra base download).
ARG BASE=nvcr.io/nvidia/vllm:26.09-py3

FROM ${BASE} AS build
ARG FAIRSEQ2_VERSION=v0.6.0
RUN apt-get update && apt-get install -y --no-install-recommends libsndfile1-dev \
 && rm -rf /var/lib/apt/lists/*
WORKDIR /src
RUN git clone --depth 1 --branch ${FAIRSEQ2_VERSION} --recurse-submodules --shallow-submodules \
      https://github.com/facebookresearch/fairseq2.git
WORKDIR /src/fairseq2/native
# Image decoding off (not needed); no TBB on aarch64 (fairseq2n's own thread pool is used).
# C++20: NVIDIA torch 2.14's ATen headers use `requires` clauses (fairseq2n asks only for >= 17).
# Under C++20 fmt checks format strings at compile time: the two runtime messages in sndfile.cc
# get an explicit "{}" and FMT_CONSTEVAL is emptied for anything similar; std::ssize (C++20) makes
# one unqualified ssize() in the bindings ambiguous.
RUN sed -i 's/throw_<std::runtime_error>(err_msg)/throw_<std::runtime_error>("{}", err_msg)/; s/throw_<std::invalid_argument>(err_msg)/throw_<std::invalid_argument>("{}", err_msg)/' \
      src/fairseq2n/data/audio/detail/sndfile.cc \
 && sed -i 's/"B", ssize(self)/"B", static_cast<py::ssize_t>(self.size())/' \
      python/src/fairseq2n/bindings/memory.cc
RUN cmake -GNinja -B build -DCMAKE_BUILD_TYPE=Release -DCMAKE_CXX_STANDARD=20 -DFAIRSEQ2N_USE_CUDA=OFF \
      -DCMAKE_CXX_FLAGS="-DFMT_CONSTEVAL=" \
      -DFAIRSEQ2N_SUPPORT_IMAGE=OFF -DFAIRSEQ2N_TREAT_WARNINGS_AS_ERRORS=OFF \
 && cmake --build build -j8
RUN cd python && pip wheel --no-deps --no-build-isolation -w /wheels . \
 && cd /src/fairseq2 && pip wheel --no-deps --no-build-isolation -w /wheels .

FROM ${BASE}
ARG OMNILINGUAL_ASR_VERSION=0.2.0
RUN apt-get update && apt-get install -y --no-install-recommends libsndfile1 \
 && rm -rf /var/lib/apt/lists/*
COPY --from=build /wheels/ /wheels/
# --no-deps everywhere: the base image's torch, numpy, transformers and huggingface_hub stay as they
# are (pip would otherwise replace NVIDIA's torch with torch==2.9.1). Only the pure-Python packages
# that fairseq2 / omnilingual-asr import at inference time are added. torchaudio is used only for
# resampling (pure Python in torchaudio 2.9+); the service sends 16 kHz audio anyway.
# --ignore-requires-python: omnilingual-asr says <=3.12, which pip reads as excluding 3.12.3.
RUN pip install --no-cache-dir --no-deps --ignore-requires-python /wheels/*.whl "omnilingual-asr==${OMNILINGUAL_ASR_VERSION}" \
      "torchaudio==2.9.1" \
 && pip install --no-cache-dir editdistance "importlib_resources~=6.4" "importlib_metadata~=7.0" \
      "mypy-extensions~=1.0" "ruamel.yaml~=0.18" "rich~=14.2" "torcheval~=0.0.6" "psutil" \
      "typing_extensions>=4.12" fsspec "sacrebleu~=2.4" "polars>=1.29" "pyarrow>=20" pandas \
      "retrying~=1.3" xxhash "clusterscope~=0.0.31" python-multipart soundfile \
 && rm -f /usr/local/lib/python3.12/dist-packages/torchaudio/lib/*.so \
 && python3 -c "import torchaudio.functional, fairseq2n; from omnilingual_asr.models.inference.pipeline import ASRInferencePipeline; print('omniasr imports ok')"
# (torchaudio's C++ extension is built for upstream torch 2.9 and does not load against NVIDIA's
# torch; without it torchaudio uses its pure-PyTorch code, which covers resample/Spectrogram.)
COPY server.py stream_eval.py /app/
WORKDIR /app
USER 1000:1000
ENV PYTHONUNBUFFERED=1 PYTHONDONTWRITEBYTECODE=1 HF_HUB_OFFLINE=1 HOME=/tmp DO_NOT_TRACK=1
CMD ["python3", "-I", "/app/server.py"]
