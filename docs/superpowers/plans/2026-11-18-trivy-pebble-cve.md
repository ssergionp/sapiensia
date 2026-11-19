# Image scan blocked by CVE-2026-97054 in /usr/bin/pebble Implementation Plan

> **For agentic workers:** LEVE flow: implement directly, no subagents. Steps use checkbox (`- [ ]`) syntax.

**Goal:** Unblock the `Trivy image` step of the CI (it fails every PR) by removing the unused `/usr/bin/pebble` from the final stage of the Dockerfile.

**Architecture:** Dockerfile only. No API, no migration, no frontend, no new user-visible text.

## Textos que o usuário vê

Nenhum.

## Findings

- `dpkg -S /usr/bin/pebble` finds no owner: the binary (Pebble v1.33.2, built with Go 1.27.3) is copied into `eclipse-temurin:25-jre` outside of dpkg, so there is no package to upgrade. The apt `pebble` package (2.6.0) is not installed and is a different build.
- Nothing in `/etc`, `/usr/local`, `/opt` or `/usr/share/doc` references it, and the `ENTRYPOINT` is `java`.
- Trivy v0.76.1 (same as CI) on the base image: only finding is HIGH CVE-2026-97054 (Go stdlib, fixed in 1.27.4) in `usr/bin/pebble`; the apt packages are clean.

## Global Constraints

- Same `TEMPORARY` comment pattern as the apt upgrade above it, saying when to remove the line.
- Nothing else in the image changes (the user, the `ENTRYPOINT` and the profile stay).

## Review Focus

1. The image still builds, starts with the `prod` profile and answers `/api/system/health`.
2. Trivy (same flags as the CI) no longer reports the CVE on the built image.

## Tasks

### Task 1: Remove pebble in the final stage

- [ ] Add `RUN rm -f /usr/bin/pebble` with the `TEMPORARY` comment after the apt upgrade in `Dockerfile`.
- [ ] `just imagem`; run the image and call `/api/system/health`.
- [ ] Trivy `image --severity HIGH,CRITICAL --ignore-unfixed` on `app:local` reports 0.
