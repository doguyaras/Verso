# Layout- and table-aware parsing sidecar (ADR-0032): docling-serve on CPU plus Tesseract's Turkish model. Built by
# deploy/compose.docling.yaml; never part of the default stack.
#
#   docker compose -f compose.yaml -f deploy/compose.docling.yaml up -d --build --wait
#
# Everything the container needs at runtime is in the image: the upstream image bakes the layout, TableFormer and OCR
# weights in at build time (MODELS_LIST of its Containerfile), and the Turkish Tesseract model is added here, pinned by
# commit and SHA-256. The running container has no route out (internal network, offline flags in the overlay).
#
# docling-serve 1.38.1 ships docling 2.142.1, the release that fixed the DoS advisories of 23 Oct 2026 (decompressed
# member size of DOCX/XLSX/EPUB, decoded image pixels). Upgrades: new tag and digest here, docs/versions.md, ADR-0032.
FROM quay.io/docling-project/docling-serve-cpu:v1.38.1@sha256:203c2ce82f19413bc3b83641962082165510352b9f93c1a840c0036be616eebf

# tessdata_best tur.traineddata (Apache-2.0, LSTM model); the image already carries eng and osd from its OS packages.
# --chmod: readable by the unprivileged runtime user (1001) of the upstream image.
ADD --checksum=sha256:e0c3338dc17503dc7d335a507c9ae01b2b46cfd07561171e1e1ac55d85e8e438 --chmod=644 \
    https://raw.githubusercontent.com/tesseract-ocr/tessdata_best/9ddc24e750eec0994223a9edc3fcb434a2244f3b/tur.traineddata \
    /usr/share/tesseract/tessdata/tur.traineddata

USER 1001
