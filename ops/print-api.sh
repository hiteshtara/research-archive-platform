#!/bin/bash

set -e

cd terraform/environments/dev

echo "API URL"

terraform output -raw api_url
