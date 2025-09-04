#!/bin/bash

CONFIG_DIR="$HOME/.fm"
CONFIG_FILE="$CONFIG_DIR/config.txt"
API_URL=""
APP_ID=""
ACCESS_TOKEN=""
HUB_IP=""

# Load config if exists
if [ -f "$CONFIG_FILE" ]; then
    source "$CONFIG_FILE"
fi

# Prompt for app id, access token, and hub ip if not set or if --config is passed
function prompt_config() {
    echo "Configure File Manager+ CLI access:"
    read -p "Enter Hubitat IP Address [${HUB_IP}]: " input_hub_ip
    if [ -n "$input_hub_ip" ]; then
        HUB_IP="$input_hub_ip"
    fi
    read -p "Enter File Manager+ App ID [${APP_ID}]: " input_app_id
    if [ -n "$input_app_id" ]; then
        APP_ID="$input_app_id"
    fi
    read -p "Enter File Manager+ Access Token [${ACCESS_TOKEN}]: " input_access_token
    if [ -n "$input_access_token" ]; then
        ACCESS_TOKEN="$input_access_token"
    fi
    mkdir -p "$CONFIG_DIR"
    echo "APP_ID=\"$APP_ID\"" > "$CONFIG_FILE"
    echo "ACCESS_TOKEN=\"$ACCESS_TOKEN\"" >> "$CONFIG_FILE"
    echo "HUB_IP=\"$HUB_IP\"" >> "$CONFIG_FILE"
}

function usage() {
    echo
    echo "Usage: $(basename $0) [--app-id ID] [--access-token TOKEN] [--hub-ip IP] [command] [args...]"
    echo "Commands:"
    echo "  list [folder]          List files (optionally in folder)"
    echo "  folders                List folders"
    echo "  upload <file> [folder] Upload file (optionally to folder)"
    echo "  delete <file>          Delete file"
    echo "  download <file>        Download file"
    echo "  create-folder <name>   Create folder"
    echo "  delete-folder <name>   Delete folder and its contents"
    echo
    echo "  --help                 Show this help message"
    echo "  --config               Prompt to view/change IP/App ID/Access Token"
    echo "  --hub-ip IP            Override Hubitat Hub IP"
    echo "  --app-id ID            Override App ID"
    echo "  --access-token TOKEN   Override Access Token"
    echo
    echo
    exit 1
}

################################################################################################
# SCRIPT START
################################################################################################

# Check for at least one argument (after possible options)
if [ $# -eq 0 ]; then
    usage
fi

# Parse command line arguments for overrides
while [[ $# -gt 0 ]]; do
    case "$1" in
        --app-id)
            APP_ID="$2"; shift 2;;
        --access-token)
            ACCESS_TOKEN="$2"; shift 2;;
        --hub-ip)
            HUB_IP="$2"; shift 2;;
        -h|--help)
            usage;;
        *)
            break;;
    esac
done

# Add --config param to force config prompt
FORCE_CONFIG=0
for arg in "$@"; do
    if [ "$arg" = "--config" ]; then
        FORCE_CONFIG=1
        break
    fi
    # stop at first non-option (the command)
    if [[ "$arg" != --* ]]; then
        break
    fi
done

# Prompt for config if missing or if --config is passed
if [ $FORCE_CONFIG -eq 1 ] || [ -z "$APP_ID" ] || [ -z "$ACCESS_TOKEN" ] || [ -z "$HUB_IP" ]; then
    prompt_config
fi

API_URL="http://$HUB_IP/apps/api/$APP_ID"

CMD="$1"
shift

case "$CMD" in
    list)
        FOLDER="$1"
        if [ -z "$FOLDER" ]; then
            curl -s "$API_URL/files?access_token=$ACCESS_TOKEN" | jq
        else
            curl -s "$API_URL/files?folder=$FOLDER&access_token=$ACCESS_TOKEN" | jq
        fi
        ;;
    folders)
        curl -s "$API_URL/folders?access_token=$ACCESS_TOKEN" | jq
        ;;
    upload)
        FILE="$1"
        FOLDER="$2"
        if [ -z "$FILE" ]; then
            echo "Missing file argument for upload"
            usage
        fi
        if [ ! -f "$FILE" ]; then
            echo "File not found: $FILE"
            exit 1
        fi
        B64_CONTENT=$(base64 < "$FILE" | tr -d '\n')
        FILENAME=$(basename "$FILE")
        if [ -z "$FOLDER" ]; then
            curl -s -X POST "$API_URL/upload?access_token=$ACCESS_TOKEN" \
                -H "Content-Type: application/json" \
                -d "{\"filename\":\"$FILENAME\",\"content\":\"$B64_CONTENT\"}" | jq
        else
            curl -s -X POST "$API_URL/upload?access_token=$ACCESS_TOKEN" \
                -H "Content-Type: application/json" \
                -d "{\"filename\":\"$FILENAME\",\"content\":\"$B64_CONTENT\",\"folder\":\"$FOLDER\"}" | jq
        fi
        ;;
    delete)
        FILE="$1"
        if [ -z "$FILE" ]; then
            echo "Missing file argument for delete"
            usage
        fi
        curl -s -X POST "$API_URL/delete?access_token=$ACCESS_TOKEN" \
            -H "Content-Type: application/json" \
            -d "{\"filename\":\"$FILE\"}" | jq
        ;;
    download)
        FILE="$1"
        if [ -z "$FILE" ]; then
            echo "Missing file argument for download"
            usage
        fi
        curl -s "$API_URL/download/$FILE?access_token=$ACCESS_TOKEN" -o "$FILE"
        echo "Downloaded to $FILE"
        ;;
    create-folder)
        FOLDER="$1"
        if [ -z "$FOLDER" ]; then
            echo "Missing folder name for create-folder"
            usage
        fi
        curl -s -X POST "$API_URL/create-folder?access_token=$ACCESS_TOKEN" \
            -H "Content-Type: application/json" \
            -d "{\"folderName\":\"$FOLDER\"}" | jq
        ;;
    delete-folder)
        FOLDER="$1"
        if [ -z "$FOLDER" ]; then
            echo "Missing folder name for delete-folder"
            usage
        fi
        curl -s -X POST "$API_URL/delete-folder?access_token=$ACCESS_TOKEN" \
            -H "Content-Type: application/json" \
            -d "{\"folderName\":\"$FOLDER\"}" | jq
        ;;
    -help|--help|-h)
        usage
        ;;
    *)
        echo "Unknown command: $CMD"
        usage
        ;;
esac
