#!/bin/bash
set -e

cd edx-platform
source scripts/jenkins-common.sh
paver update_bokchoy_db_cache
