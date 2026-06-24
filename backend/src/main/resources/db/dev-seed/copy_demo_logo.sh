#!/usr/bin/env bash
#
# Phase 16A PR2 — copy the committed demo invoice logo PNG into local backend file storage so the
# backend-generated invoice PDF can read and embed it. Dev/demo only.
#
# Pairs with branding_demo.sql, which sets business.logo_path = '/uploads/1/branding/logo.png'
# for the 'aussie-floors-group' demo business (business_id 1).
#
# The on-screen logo is served by Vite from frontend/public/uploads/1/branding/logo.png and needs
# NO copy. This script only handles the BACKEND side (the PDF reads from disk via FileStorageService).
#
# !!! INTENTIONAL DOUBLE 'uploads/uploads' IN THE DESTINATION — DO NOT "FIX" IT !!!
#   app.storage.base-dir = ${user.home}/flooring-sales-portal-data/uploads   (already ends in /uploads)
#   FileStorageService resolves the virtual path '/uploads/1/branding/logo.png' (leading slash
#   stripped) UNDER that base dir, producing:
#       $HOME/flooring-sales-portal-data/uploads/uploads/1/branding/logo.png
#   This matches how attachments already store on disk (…/uploads/uploads/{businessId}/orders/…).
#
# No dependency on Docker / psql / backend server / frontend server. Run from the repo root:
#     bash backend/src/main/resources/db/dev-seed/copy_demo_logo.sh
set -euo pipefail

SRC="frontend/public/uploads/1/branding/logo.png"
DEST_DIR="${HOME}/flooring-sales-portal-data/uploads/uploads/1/branding"
DEST="${DEST_DIR}/logo.png"

echo "Source:      ${SRC}"
echo "Destination: ${DEST}"

if [[ ! -f "${SRC}" ]]; then
  echo "ERROR: source PNG not found at '${SRC}'." >&2
  echo "       Run this script from the repository root." >&2
  exit 1
fi

mkdir -p "${DEST_DIR}"
cp "${SRC}" "${DEST}"

if [[ ! -f "${DEST}" ]]; then
  echo "ERROR: copy did not produce '${DEST}'." >&2
  exit 1
fi

echo "OK: demo logo copied into backend storage."
ls -l "${DEST}"
