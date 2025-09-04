// hubitat start
// hub: 192.168.0.200
// type: app
// id: 956
// hubitat end

import groovy.json.JsonOutput
import groovy.transform.Field

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
        action: [POST: "renameFile"]
    }
}

@Field static String FOLDER_PREFIX = "folder_"
@Field static String FOLDER_NAME_SUFFIX = "_folder.marker"

def installed() {
    log.debug "File Manager App installed"
    initialize()
}

def updated() {
    log.debug "File Manager App updated"
    initialize()
}

def initialize() {
    state.showHiddenFiles = showHiddenFiles ?: false
    state.fileTypes = fileTypes ?: ""
    state.maxFileSize = maxFileSize ?: 10
    state.sortOrder = "name"
    state.sortDirection = "asc"

    createAccessToken()
}

// Used instead of spaces in filenames/folders for Hubitat compatibility
def getSpaceChar() { return '.' }

// Main method to get all files from Hubitat
def getAllFiles() {
    try {
        def allFiles = getHubFiles()
        //log.debug "getAllFiles: ${allFiles.size()} files"
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
    }.sort { it.name }
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
        uploadHubFile(folderMarkerName, markerContent.getBytes("UTF-8"))
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
            log.debug "Deleting file: ${file.name}"
            deleteHubFile(file.name) // returns void
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
        if (content.length > (state.maxFileSize * 1024 * 1024)) {
            log.error "File ${filename} exceeds maximum size of ${state.maxFileSize}MB"
            return false
        }
        uploadHubFile(finalFilename, content)
        log.info "Uploaded file: ${finalFilename} (${content.length} bytes)"
        return true
    } catch (Exception e) {
        log.error "Error uploading file ${filename}: ${e.message}"
        return false
    }
}

// API endpoint: view dashboard
def dashboard() {
    def filename = "file-manager-dashboard.html"
    try {
        byte[] bytes = downloadHubFile(filename)
        if (!bytes) {
            render status: 404, text: "File not found: ${filename}"
            return
        }
        //log.debug "Viewing dashboard file: ${filename}, size: ${bytes.length} bytes"
        render contentType: "text/html", data: bytes
    } catch (Exception e) {
        log.error "Error viewing dashboard ${filename}: ${e.message}"
        render status: 500, text: "Error viewing dashboard"
    }
}

// API endpoint: List files
def listFiles() {
    // Accept folder param from either JSON body or query string
    def folder = (request.JSON?.folder) ?: params.folder
    //log.debug "Listing files in folder: ${folder ?: 'all'}"

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

    log.debug "Uploading file: ${filename} to folder: ${folder ?: 'root'}"

    if (!filename || !content) {
        render status: 400, contentType: "application/json", data: JsonOutput.toJson([error: "Missing filename or content"])
        return
    }

    // Decode base64 content
    byte[] fileContent
    try {
        fileContent = content.decodeBase64()
    } catch (Exception e) {
        render status: 400, contentType: "application/json", data: JsonOutput.toJson([error: "Invalid content encoding"])
        return
    }

    def success = uploadFileWithOrganization(filename, fileContent, folder)

    if (success) {
        render contentType: "application/json", data: JsonOutput.toJson([success: true, message: "File uploaded successfully"])
    } else {
        render status: 500, contentType: "application/json", data: JsonOutput.toJson([error: "Failed to upload file"])
    }
}

// API endpoint: Delete file
def deleteFile() {
    def params = request.JSON ?: [:]
    def filename = params.filename

    if (!filename) {
        render status: 400, contentType: "application/json", data: JsonOutput.toJson([error: "Missing filename"])
        return
    }

    log.debug "Deleting file: ${filename}"
    def allFiles = getAllFiles()
    def allFileNames = allFiles.collect { it.name }

    if (!allFileNames.contains(filename)) {
        log.error "File not found: ${filename}"
        render status: 404, contentType: "application/json", data: JsonOutput.toJson([error: "File not found", files: allFileNames])
        return
    }

    try {
        deleteHubFile(filename) // returns void
        render contentType: "application/json", data: JsonOutput.toJson([success: true, message: "File deleted successfully"])
    } catch (Exception e) {
        log.error "Error deleting file ${filename}: ${e.message}"
        render status: 500, contentType: "application/json", data: JsonOutput.toJson([error: "Failed to delete file", exception: e.message])
    }
}

// API endpoint: Download file
def downloadFile() {
    def filename = params.filename
    if (!filename) {
        render status: 400, text: "Missing filename"
        return
    }

    try {
        byte[] bytes = downloadHubFile(filename)
        if (!bytes) {
            render status: 404, text: "File not found: ${filename}"
            return
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
        render status: 500, text: "Error downloading file"
    }
}

// API endpoint: Create folder
def createFolder() {
    def params = request.JSON ?: [:]
    def folderName = params.folderName

    if (!folderName) {
        render status: 400, contentType: "application/json", data: JsonOutput.toJson([error: "Missing folder name"])
        return
    }
    // Validation: cannot start with period
    if (folderName.startsWith('.')) {
        render status: 400, contentType: "application/json", data: JsonOutput.toJson([error: "Folder names cannot start with a period."])
        return
    }
    // Replace spaces with FILE_SAFE_CHAR
    def safeFolderName = folderName.replaceAll(' ', getSpaceChar())

    def success = createFolder(safeFolderName)

    if (success) {
        render contentType: "application/json", data: JsonOutput.toJson([success: true, message: "Folder created successfully", folderName: safeFolderName])
    } else {
        render status: 500, contentType: "application/json", data: JsonOutput.toJson([error: "Failed to create folder"])
    }
}

// API endpoint: Delete folder
def deleteFolder() {
    def params = request.JSON ?: [:]
    def folderName = params.folderName

    if (!folderName) {
        render status: 400, contentType: "application/json", data: JsonOutput.toJson([error: "Missing folder name"])
        return
    }

    def deletedCount = deleteFolder(folderName)

    if (deletedCount >= 0) {
        render contentType: "application/json", data: JsonOutput.toJson([success: true, message: "Folder deleted successfully", deletedCount: deletedCount])
    } else {
        render status: 500, contentType: "application/json", data: JsonOutput.toJson([error: "Failed to delete folder"])
    }
}

// API endpoint: Move file
def moveFile() {
    def params = request.JSON ?: [:]
    def filename = params.filename
    def targetFolder = params.targetFolder

    if (!filename) {
        log.error "moveFile: Missing filename"
        render status: 400, contentType: "application/json", data: JsonOutput.toJson([error: "Missing filename"])
        return
    }

    try {
        // Download the file
        byte[] content = downloadHubFile(filename)
        if (!content) {
            render status: 404, contentType: "application/json", data: JsonOutput.toJson([error: "File not found"])
            return
        }

        // Always remove any existing folder prefix from filename
        def baseFilename = filename.replaceFirst(/^${FOLDER_PREFIX}[^_]+_/, '')
        log.debug "moveFile: computed baseFilename: ${baseFilename} from filename: ${filename}"

        def newFilename
        if (targetFolder) {
            newFilename = "${FOLDER_PREFIX}${targetFolder}_${baseFilename}"
        } else {
            newFilename = baseFilename
        }

        log.debug "moveFile: file: ${filename} -> ${newFilename}"

        // If the new filename is the same as the old, just return success
        if (newFilename == filename) {
            render contentType: "application/json", data: JsonOutput.toJson([success: true, message: "File already in target location"])
            return
        }

        // Upload to new location
        uploadHubFile(newFilename, content)

        // Delete original file
        deleteHubFile(filename)

        render contentType: "application/json", data: JsonOutput.toJson([success: true, message: "File moved successfully"])
    } catch (Exception e) {
        log.error "Error moving file ${filename}: ${e.message}"
        render status: 500, contentType: "application/json", data: JsonOutput.toJson([error: "Failed to move file"])
    }
}

// API endpoint: Rename file or folder
def renameFile() {
    def params = request.JSON ?: [:]
    def oldName = params.oldName
    def newName = params.newName
    def isFolder = params.isFolder

    if (!oldName || !newName) {
        render status: 400, contentType: "application/json", data: JsonOutput.toJson([error: "Missing oldName or newName"])
        return
    }

    log.debug "Renaming ${isFolder ? 'folder' : 'file'}: ${oldName} to ${newName}"

    try {
        if (isFolder) {
            // Rename folder marker file
            def oldMarker = "${FOLDER_PREFIX}${oldName}${FOLDER_NAME_SUFFIX}"
            def newMarker = "${FOLDER_PREFIX}${newName}${FOLDER_NAME_SUFFIX}"
            byte[] markerContent = downloadHubFile(oldMarker)
            if (markerContent) {
                uploadHubFile(newMarker, markerContent)
                deleteHubFile(oldMarker)
            }
            // Rename all files in the folder
            def folderPrefixOld = "${FOLDER_PREFIX}${oldName}_"
            def folderPrefixNew = "${FOLDER_PREFIX}${newName}_"
            def allFiles = getAllFiles()
            def filesToRename = allFiles.findAll { it.name.startsWith(folderPrefixOld) && !it.name.endsWith(FOLDER_NAME_SUFFIX) }
            log.debug "Renaming ${filesToRename.size()} files in folder ${oldName}"
            filesToRename.each { file ->
                def newFileName = file.name.replaceFirst(folderPrefixOld, folderPrefixNew)
                byte[] content = downloadHubFile(file.name)
                if (content) {
                    uploadHubFile(newFileName, content)
                    deleteHubFile(file.name)
                }
            }
            render contentType: "application/json", data: JsonOutput.toJson([success: true, message: "Folder and contents renamed successfully"])
        } else {
            // Rename a file
            byte[] content = downloadHubFile(oldName)
            if (!content) {
                render status: 404, contentType: "application/json", data: JsonOutput.toJson([error: "File not found"])
                return
            }
            uploadHubFile(newName, content)
            deleteHubFile(oldName)
            render contentType: "application/json", data: JsonOutput.toJson([success: true, message: "File renamed successfully"])
        }
    } catch (Exception e) {
        log.error "Error renaming: ${oldName} to ${newName}: ${e.message}"
        render status: 500, contentType: "application/json", data: JsonOutput.toJson([error: "Failed to rename", exception: e.message])
    }
}

// Utility method to get file statistics
def getFileStats() {
    def allFiles = getAllFiles()
    def stats = [
        totalFiles: allFiles.size(),
        totalSize : allFiles.sum { it.size ?: 0 },
        folders   : getFolders().size(),
        fileTypes : [:]
    ]

    // Count file types
    allFiles.each { file ->
        def ext = file.name.tokenize('.').last().toLowerCase()
        stats.fileTypes[ext] = (stats.fileTypes[ext] ?: 0) + 1
    }

    return stats
}

// Method to get public URLs for files
def getPublicUrl(String filename) {
    def safeName = safeName(filename)
    def token = state.accessToken ?: createAccessToken()
    return "${fullApiServerUrl("download/${safeName}")}" + "?access_token=${token}"
}

// Safe filename utility
static String safeName(String filename) {
    return filename?.replaceAll("[^a-zA-Z0-9_.\\-]", "")
}
