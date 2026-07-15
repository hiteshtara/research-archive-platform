from pathlib import Path

import yaml

CONFIG_FILE = (
    Path(__file__)
    .resolve()
    .parents[2]
    / "config"
    / "settings.yaml"
)


def load_settings():

    with open(CONFIG_FILE) as f:
        return yaml.safe_load(f)
