# Hubitat File Manager App

A file management app for Hubitat that allows for multiple file uploads, folders and more

## Features

- **List files** - Browse all files stored on your Hubitat hub
- **Upload multiple files** - Multiple file upload support
- **Drag & drop interface**
- **Folder support** - Create virtual folders
- **Support for spaces in filenames** - Folders and files with spaces can be uploaded
- **File filtering/searching** - Filter/search files by name
- **Sorting options** - Sort by name, size, or date
- **Multi-select** - Select multiple files for bulk operations (delete, move)
- **File statistics** - Tracks total folder size

## Installation

- see community post: https://community.hubitat.com/t/file-manager-beta/156586

1) find and install "File Manager+" from Hubitat Package Manager (HPM)
2) Hubitat Apps page -> New user app -> File Manager+
3) The first time the app opens, just hit DONE to install the app
4) The next time you open the app you should see a button to open the new file manager. You can bookmark that page for use in future

## API Endpoints

The app provides RESTful API endpoints for programmatic access:

### File Operations
- `GET /apps/api/APP_ID/files` - List files (with optional folder parameter)
- `POST /apps/api/APP_ID/upload` - Upload a file
- `POST /apps/api/APP_ID/delete` - Delete a file
- `GET /apps/api/APP_ID/download/{filename}` - Download a file

### Folder Operations
- `GET /apps/api/APP_ID/folders` - List all folders
- `POST /apps/api/APP_ID/create-folder` - Create a new folder
- `POST /apps/api/APP_ID/delete-folder` - Delete a folder and its contents

### Example API Usage

```bash
# List all files
curl "http://HUB_IP/apps/api/APP_ID/files"

# List files in a specific folder
curl "http://HUB_IP/apps/api/APP_ID/files?folder=images&access_token=APP_TOKEN"

# Upload a file
curl -X POST "http://HUB_IP/apps/api/APP_ID/upload?access_token=APP_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"filename":"test.txt","content":"SGVsbG8gV29ybGQ="}'

# Delete a file
curl -X POST "http://HUB_IP/apps/api/APP_ID/delete?access_token=APP_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"filename":"test.txt"}'

# Create a folder
curl -X POST "http://HUB_IP/apps/api/APP_ID/create-folder?access_token=APP_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"folderName":"my_folder"}'

```

### Bash Script

A bash script is included ([fm.sh](fm.sh)) which wraps the API calls

```
Usage: fm.sh [--app-id ID] [--access-token TOKEN] [--hub-ip IP] [command] [args...]
Commands:
list [folder]          List files (optionally in folder)
folders                List folders
upload <file> [folder] Upload file (optionally to folder)
delete <file>          Delete file
download <file>        Download file
create-folder <name>   Create folder
delete-folder <name>   Delete folder and its contents

--help                 Show this help message
--config               Prompt to view/change IP/App ID/Access Token
--hub-ip IP            Override Hubitat Hub IP
--app-id ID            Override App ID
--access-token TOKEN   Override Access Token
```

The first time you run the script it will prompt you for your Hub IP, App ID and Access Token and save for future use. 

You can later use the `--config` option to view/change these settings.

Examples:

```
# List all files
./fm.sh list

# Upload a file
./fm.sh upload myfile.txt
```

I add an alias to my `.profile` so I can run it from anywhere (`fm upload myfile.txt`):
```
alias fm='$WORKING/hubitat-drivers/file-manager-app/fm.sh'
```