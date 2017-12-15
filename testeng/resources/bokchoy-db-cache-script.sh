#!/bin/bash
set -e

virtualenv bokchoy_db_env -q
source bokchoy_db_env/bin/activate

cd edx-platform
paver pre_reqs
