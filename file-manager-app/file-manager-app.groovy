// hubitat start
// hub: 192.168.0.200
// type: app
// id: 956
// hubitat end

import groovy.json.JsonOutput
import groovy.transform.Field
import java.nio.file.AccessDeniedException

definition(
    name: "File Manager+",
    namespace: "jpage4500",
    author: "Joe Page",
    description: "File manager for Hubitat with multiple upload, delete, and folder support",
    oauth: true,
    iconUrl: '',
    iconX2Url: '',
    iconX3Url: ''
)

preferences {
    page(name: "mainPage", install: true, uninstall: true) {
        section() {
            if (!state.accessToken) {
                paragraph "Please click 'Done' to initialize the app and create an access token.\n\nThen re-open the app to view File Manager+ link"
            } else {
                href(
                    title: 'View File Manager+',
                    url: "${getFullLocalApiServerUrl()}/dashboard?access_token=${state.accessToken}"
                )

                paragraph "<a target=_blank href=${getFullLocalApiServerUrl()}/dashboard?access_token=${state.accessToken}>Open in new tab</a>"

                // NOTE: cloud endpoint has a limit to how much data can be returned (128k??)
                //href(
                //    title      : 'View File Manager+ (cloud)',
                //    url        : "${getFullApiServerUrl()}/dashboard?access_token=${state.accessToken}",
                //    description: 'View File Manager+',
                //)

                paragraph "Access Token: ${state.accessToken}"
                paragraph "App ID: ${app.id}"
            }
        }
        section("Preferences") {
            input name: "maxFileSize", type: "number", title: "Max upload file size (MB)", defaultValue: 10, range: "1..100"
            input name: "debugOutput", type: "bool", title: "Enable Debug Logging?", defaultValue: false, submitOnChange: true
        }
    }
}

mappings {
    path("/dashboard") {
        action:
        [GET: "dashboard"]
    }
    path("/files") {
        action:
        [GET: "listFiles"]
    }
    path("/upload") {
        action:
        [POST: "uploadFile"]
    }
    path("/delete") {
        action:
        [POST: "deleteFile"]
    }
    path("/download/:filename") {
        action:
        [GET: "downloadFile"]
    }
    path("/folders") {
        action:
        [GET: "listFolders"]
    }
    path("/create-folder") {
        action:
        [POST: "createFolder"]
    }
    path("/delete-folder") {
        action:
        [POST: "deleteFolder"]
    }
    path("/move-file") {
        action:
        [POST: "moveFile"]
    }
    path("/rename") {
        action:
        [POST: "renameFile"]
    }
}

@Field static String FOLDER_PREFIX = "folder_"
@Field static String FOLDER_NAME_SUFFIX = "_folder.marker"
@Field static String DASHBOARD_FILENAME = "file-manager-dashboard.html"

def installed() {
    logDebug("File Manager App installed")
    initialize()
}

def updated() {
    logDebug("File Manager App updated")
    initialize()
}

def initialize() {
    state.showHiddenFiles = showHiddenFiles ?: false
    state.fileTypes = fileTypes ?: ""
    state.sortOrder = "name"
    state.sortDirection = "asc"

    if (state.accessToken == null) createAccessToken()
}

// Used instead of spaces in filenames/folders for Hubitat compatibility
def getSpaceChar() { return '.' }

// Main method to get all files from Hubitat
def getAllFiles() {
    try {
        def allFiles = getHubFiles()
        //logDebug("getAllFiles: ${allFiles.size()} files"
        return allFiles
    } catch (Exception e) {
        log.error "Error getting files: ${e.message}"
        return []
    }
}

// Filter files based on preferences
def getFilteredFiles() {
    def allFiles = getAllFiles()
    def filteredFiles = allFiles

    // Filter by hidden files
    if (!state.showHiddenFiles) {
        filteredFiles = filteredFiles.findAll { !it.name.startsWith('.') }
    }

    // Sort files
    filteredFiles = sortFiles(filteredFiles)

    return filteredFiles
}

// Sort files based on preferences
def sortFiles(files) {
    def sortedFiles = files.sort { a, b ->
        def result = 0
        switch (state.sortOrder) {
            case "size":
                result = (a.size ?: 0) <=> (b.size ?: 0)
                break
            case "date":
                result = (a.lastModified ?: 0) <=> (b.lastModified ?: 0)
                break
            default: // name
                result = (a.name ?: "") <=> (b.name ?: "")
                break
        }
        return state.sortDirection == "desc" ? -result : result
    }
    return sortedFiles
}

// Get folders (only .marker files)
def getFolders() {
    def allFiles = getAllFiles()
    def folders = allFiles.findAll {
        it.name.startsWith(FOLDER_PREFIX) && it.name.endsWith(FOLDER_NAME_SUFFIX)
    }
    return folders.collect { file ->
        def folderName = file.name.substring(FOLDER_PREFIX.length())
        // Remove the marker suffix
        folderName = folderName.replace(FOLDER_NAME_SUFFIX, '')
        [
            name        : folderName,
            fullName    : file.name,
            size        : file.size,
            lastModified: file.date,
            isFolder    : true
        ]
    }.sort { it.name.toLowerCase() }
}

// Get files in a specific folder
def getFilesInFolder(String folderName) {
    def allFiles = getAllFiles()
    def folderPrefix = "${FOLDER_PREFIX}${folderName}_"
    def folderFiles = allFiles.findAll { it.name.startsWith(folderPrefix) && !it.name.endsWith(FOLDER_NAME_SUFFIX) }

    return folderFiles.collect { file ->
        def fileName = file.name.substring(folderPrefix.length())
        [
            name        : fileName,
            fullName    : file.name,
            size        : file.size,
            lastModified: file.date,
            folder      : folderName,
            isFolder    : false
        ]
    }
}

// Create a folder (creates a marker file)
def createFolder(String folderName) {
    try {
        def folderMarkerName = "${FOLDER_PREFIX}${folderName}${FOLDER_NAME_SUFFIX}"
        def markerContent = "Folder created: ${new Date()}"
        safeUploadHubFile(folderMarkerName, markerContent.getBytes("UTF-8"))
        log.info "Created folder: ${folderName}"
        return true
    } catch (Exception e) {
        log.error "Error creating folder ${folderName}: ${e.message}"
        return false
    }
}

// Delete a folder and all its contents
// This deletes the marker file and ALL files in the folder (files with the folder's prefix)
def deleteFolder(String folderName) {
    try {
        def allFiles = getAllFiles()
        def folderPrefix = "${FOLDER_PREFIX}${folderName}_"
        // Find all files in the folder, including the marker file
        def folderFiles = allFiles.findAll { it.name.startsWith(folderPrefix) }

        def deletedCount = 0
        folderFiles.each { file ->
            logDebug("Deleting file: ${file.name}")
            safeDeleteHubFile(file.name)
            deletedCount++
        }

        log.info "Deleted folder ${folderName} with ${deletedCount} files"
        return deletedCount
    } catch (Exception e) {
        log.error "Error deleting folder ${folderName}: ${e.message}"
        return 0
    }
}

// Upload file with optional folder organization
def uploadFileWithOrganization(String filename, byte[] content, String folder) {
    try {
        // Replace spaces with FILE_SAFE_CHAR in filename and folder
        def safeFilename = filename?.replaceAll(' ', getSpaceChar())
        def safeFolder = folder ? folder.replaceAll(' ', getSpaceChar()) : null
        def finalFilename = safeFilename
        if (safeFolder) {
            finalFilename = "${FOLDER_PREFIX}${safeFolder}_${safeFilename}"
        }
        // Check file size
        def maxFileSize = settings?.maxFileSize ? settings.maxFileSize : 10
        if (content.length > (maxFileSize * 1024 * 1024)) {
            log.error "File ${filename} exceeds maximum size of ${maxFileSize}MB"
            return false
        }
        safeUploadHubFile(finalFilename, content)
        log.info "Uploaded file: ${finalFilename} (${content.length} bytes)"
        return true
    } catch (Exception e) {
        log.error "Error uploading file ${filename}: ${e.message}"
        return false
    }
}

// API endpoint: view dashboard
def dashboard() {
    try {
        byte[] bytes = safeDownloadHubFile(DASHBOARD_FILENAME)
        if (!bytes) {
            return renderResponse(404, "File not found: ${DASHBOARD_FILENAME}")
        }
        render contentType: "text/html", data: bytes
    } catch (Exception e) {
        log.error "Error viewing dashboard ${DASHBOARD_FILENAME}: ${e.message}"
        return renderResponse(500, "Error viewing dashboard: ${DASHBOARD_FILENAME}")
    }
}

// API endpoint: List files
def listFiles() {
    // Accept folder param from either JSON body or query string
    def folder = (request.JSON?.folder) ?: params.folder
    //logDebug("Listing files in folder: ${folder ?: 'all'}"

    def files
    if (folder) {
        files = getFilesInFolder(folder)
    } else {
        // Get all files and folders
        def allFiles = getFilteredFiles()
        def folders = getFolders()
        files = []
        // Add folders first
        folders.each { folderInfo ->
            files << [
                name        : folderInfo.name,
                fullName    : folderInfo.fullName,
                size        : folderInfo.size,
                lastModified: folderInfo.lastModified,
                isFolder    : true
            ]
        }
        // Add only files that are not in any folder and not marker files
        allFiles.each { file ->
            if (!file.name.startsWith(FOLDER_PREFIX) && !file.name.endsWith(FOLDER_NAME_SUFFIX)) {
                files << [
                    name        : file.name,
                    fullName    : file.name,
                    size        : file.size,
                    lastModified: file.date,
                    isFolder    : false
                ]
            }
        }
    }

    def response = [
        files: files,
        total: files.size()
    ]

    render contentType: "application/json", data: JsonOutput.toJson(response)
}

// API endpoint: List folders
def listFolders() {
    def folders = getFolders()
    def response = [
        folders: folders,
        total  : folders.size()
    ]

    render contentType: "application/json", data: JsonOutput.toJson(response)
}

// API endpoint: Upload file
def uploadFile() {
    def params = request.JSON ?: [:]
    def filename = params.filename
    def content = params.content
    def folder = params.folder

    logDebug("Uploading file: ${filename} to folder: ${folder ?: 'root'}")

    if (!filename || !content) {
        log.error "Missing filename: ${filename} or content"
        return renderResponse(400, "Missing filename or content")
    }

    // Decode base64 content
    byte[] fileContent
    try {
        fileContent = content.decodeBase64()
    } catch (Exception e) {
        log.error "Invalid content encoding: ${e.message}"
        return renderResponse(400, "Invalid content for ${filename}")
    }

    def success = uploadFileWithOrganization(filename, fileContent, folder)

    if (success) {
        return renderResponse(200, "File uploaded successfully")
    } else {
        return renderResponse(500, "Failed to upload file: ${filename}")
    }
}

// API endpoint: Delete file
def deleteFile() {
    def params = request.JSON ?: [:]
    def filename = params.filename

    if (!filename) {
        log.error "Missing filename"
        return renderResponse(400, "Missing filename")
    } else if (filename == DASHBOARD_FILENAME) {
        log.error "Cannot delete dashboard file: ${DASHBOARD_FILENAME}"
        return renderResponse(400, "Cannot delete dashboard file: ${DASHBOARD_FILENAME}")
    }

    logDebug("Deleting file: ${filename}")
    def allFiles = getAllFiles()
    def allFileNames = allFiles.collect { it.name }

    if (!allFileNames.contains(filename)) {
        log.error "File not found: ${filename}"
        return renderResponse(404, "file not found: ${filename}")
    }

    try {
        safeDeleteHubFile(filename)
        return renderResponse(200, "File deleted successfully")
    } catch (Exception e) {
        log.error "Error deleting file ${filename}: ${e.message}"
        return renderResponse(500, "Failed to delete file: ${filename}: ${e.message}")
    }
}

// API endpoint: Download file
def downloadFile() {
    def filename = params.filename
    if (!filename) {
        return renderResponse(400, "Missing filename")
    }

    try {
        byte[] bytes = safeDownloadHubFile(filename)
        if (!bytes) {
            return renderResponse(404, "File not found: ${filename}")
        }

        def ext = filename.tokenize('.').last().toLowerCase()
        def contentType = [
            jpg : "image/jpeg",
            jpeg: "image/jpeg",
            png : "image/png",
            gif : "image/gif",
            pdf : "application/pdf",
            txt : "text/plain",
            html: "text/html",
            css : "text/css",
            js  : "application/javascript",
            json: "application/json",
            xml : "application/xml"
        ][ext] ?: "application/octet-stream"

        render contentType: contentType, data: bytes
    } catch (Exception e) {
        log.error "Error downloading file ${filename}: ${e.message}"
        return renderResponse(500, "File not found: ${filename}, error: ${e.message}")
    }
}

// API endpoint: Create folder
def createFolder() {
    def params = request.JSON ?: [:]
    def folderName = params.folderName

    if (!folderName) {
        return renderResponse(400, "Missing folder name")
    }
    // Validation: cannot start with period
    if (folderName.startsWith('.')) {
        return renderResponse(400, "Folder names cannot start with a period")
    }
    // Replace spaces with FILE_SAFE_CHAR
    def safeFolderName = folderName.replaceAll(' ', getSpaceChar())

    def success = createFolder(safeFolderName)

    if (success) {
        return renderResponse(200, "Folder created successfully")
    } else {
        return renderResponse(500, "Failed to create folder: ${folderName}")
    }
}

// API endpoint: Delete folder
def deleteFolder() {
    def params = request.JSON ?: [:]
    def folderName = params.folderName

    if (!folderName) {
        return renderResponse(400, "Missing folder name")
    }

    def deletedCount = deleteFolder(folderName)

    if (deletedCount >= 0) {
        return renderResponse(200, "Folder deleted successfully (${deletedCount} files)")
    } else {
        return renderResponse(500, "Failed to delete folder: ${folderName}")
    }
}

// API endpoint: Move file
def moveFile() {
    def params = request.JSON ?: [:]
    def filename = params.filename
    def targetFolder = params.targetFolder

    if (!filename) {
        log.error "moveFile: Missing filename"
        return renderResponse(400, "Missing filename")
    } else if (filename == DASHBOARD_FILENAME) {
        log.error "Cannot move dashboard file: ${DASHBOARD_FILENAME}"
        return renderResponse(400, "Cannot move dashboard file: ${DASHBOARD_FILENAME}")
    }

    try {
        // Download the file
        byte[] content = safeDownloadHubFile(filename)
        if (!content) {
            return renderResponse(404, "File not found: ${filename}")
        }

        // Always remove any existing folder prefix from filename
        def baseFilename = filename.replaceFirst(/^${FOLDER_PREFIX}[^_]+_/, '')
        def newFilename
        if (targetFolder) {
            newFilename = "${FOLDER_PREFIX}${targetFolder}_${baseFilename}"
        } else {
            newFilename = baseFilename
        }

        logDebug("moveFile: file: ${filename} -> ${newFilename}")

        // If the new filename is the same as the old, just return success
        if (newFilename == filename) {
            return renderResponse(200, "File already in target location")
        }

        // Upload to new location
        safeUploadHubFile(newFilename, content)

        // Delete original file
        safeDeleteHubFile(filename)

        return renderResponse(200, "File moved successfully")
    } catch (Exception e) {
        log.error "Error moving file ${filename}: ${e.message}"
        return renderResponse(500, "Failed to move file: ${e.message}")
    }
}

// API endpoint: Rename file or folder
def renameFile() {
    def params = request.JSON ?: [:]
    def oldName = params.oldName
    def newName = params.newName
    def isFolder = params.isFolder

    if (!oldName || !newName) {
        return renderResponse(400, "Missing oldName or newName")
    } else if (oldName == DASHBOARD_FILENAME) {
        log.error "Cannot rename dashboard file: ${DASHBOARD_FILENAME}"
        return renderResponse(400, "Cannot rename dashboard file: ${DASHBOARD_FILENAME}")
    }

    logDebug("Renaming ${isFolder ? 'folder' : 'file'}: ${oldName} to ${newName}")

    try {
        if (isFolder) {
            // Rename folder marker file
            def oldMarker = "${FOLDER_PREFIX}${oldName}${FOLDER_NAME_SUFFIX}"
            def newMarker = "${FOLDER_PREFIX}${newName}${FOLDER_NAME_SUFFIX}"
            byte[] markerContent = safeDownloadHubFile(oldMarker)
            if (markerContent) {
                safeUploadHubFile(newMarker, markerContent)
                safeDeleteHubFile(oldMarker)
            }
            // Rename all files in the folder
            def folderPrefixOld = "${FOLDER_PREFIX}${oldName}_"
            def folderPrefixNew = "${FOLDER_PREFIX}${newName}_"
            def allFiles = getAllFiles()
            def filesToRename = allFiles.findAll { it.name.startsWith(folderPrefixOld) && !it.name.endsWith(FOLDER_NAME_SUFFIX) }
            logDebug("Renaming ${filesToRename.size()} files in folder ${oldName}")
            filesToRename.each { file ->
                def newFileName = file.name.replaceFirst(folderPrefixOld, folderPrefixNew)
                byte[] content = safeDownloadHubFile(file.name)
                if (content) {
                    safeUploadHubFile(newFileName, content)
                    safeDeleteHubFile(file.name)
                }
            }
            return renderResponse(200, "Folder and contents renamed successfully")
        } else {
            // Rename a file
            byte[] content = safeDownloadHubFile(oldName)
            if (!content) {
                return renderResponse(404, "File not found")
            }
            safeUploadHubFile(newName, content)
            safeDeleteHubFile(oldName)
            return renderResponse(200, "File renamed successfully")
        }
    } catch (Exception e) {
        log.error "Error renaming: ${oldName} to ${newName}: ${e.message}"
        return renderResponse(500, "Failed to rename: ${e.message}")
    }
}

// return a consistent JSON response:
// [success: true/false, message: "text"]
def renderResponse(status, message) {
    def json = [success: status == 200, message: message]
    render status: status, contentType: "application/json", data: JsonOutput.toJson(json)
}

// Method to get public URLs for files
def getPublicUrl(String filename) {
    def safeName = safeName(filename)
    return "${fullApiServerUrl("download/${safeName}")}" + "?access_token=${state.accessToken}"
}

// Safe filename utility
static String safeName(String filename) {
    return filename?.replaceAll("[^a-zA-Z0-9_.\\-]", "")
}

byte[] safeDownloadHubFile(String fileName) {
    for (int i = 1; i <= 3; i++) {
        try {
            return downloadHubFile(fileName)
        } catch (AccessDeniedException ex) {
            log.warn "Failed to download ${fileName}: ${ex.message}. Retrying (${i} / 3) ..."
            pauseExecution(500)
        }
    }

    log.error "Failed to download ${fileName} after 3 attempts"
    return null
}


void safeUploadHubFile(String fileName, byte[] bytes) {
    for (int i = 1; i <= 3; i++) {
        try {
            uploadHubFile(fileName, bytes)
            return
        } catch (AccessDeniedException ex) {
            log.warn "Failed to upload ${fileName}: ${ex.message}. Retrying (${i} / 3) ..."
            pauseExecution(500)
        }
    }

    log.error "Failed to upload ${fileName} after 3 attempts - possible data loss"
}


void safeDeleteHubFile(String fileName) {
    for (int i = 1; i <= 3; i++) {
        try {
            deleteHubFile(fileName)
            return
        } catch (AccessDeniedException ex) {
            log.warn "Failed to delete ${fileName}: ${ex.message}. Retrying (${i} / 3) ..."
            pauseExecution(500)
        }
    }

    log.error "Failed to delete ${fileName} after 3 attempts"
}

private logDebug(msg) {
    if (settings?.debugOutput) {
        log.debug("$msg")
    }
}
