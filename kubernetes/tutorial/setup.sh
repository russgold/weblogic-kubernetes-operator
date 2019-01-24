#!/bin/bash
# Copyright 2019, Oracle Corporation and/or its affiliates.  All rights reserved.
# Licensed under the Universal Permissive License v 1.0 as shown at http://oss.oracle.com/licenses/upl.
set -e   # Exit immediately if a command exits with a non-zero status.

SECONDS=0
./domain.sh checkPV

# create domains
./operator.sh pullImages
./operator.sh create
./domain.sh createPV
./domain.sh createAll
./domain.sh waitUntilAllReady

# setup load balancer
./traefik.sh createCon
./traefik.sh createIng

echo "$0 took $(($SECONDS / 60)) minutes and $(($SECONDS % 60)) seconds to finish."