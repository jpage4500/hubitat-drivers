// hubitat start
// hub: 192.168.0.200
// type: device
// id: 1784
// hubitat end

metadata {
    definition(name: "File Manager+ Device", namespace: "jpage4500", author: "Joe Page") {
        capability "Refresh"
        
        // File management attributes
        attribute "totalFiles", "number"
        attribute "totalSize", "number"
        attribute "totalFolders", "number"
        attribute "currentFolder", "string"
        attribute "fileList", "string"
        attribute "folderList", "string"
        attribute "lastOperation", "string"
        attribute "lastOperationTime", "string"
        attribute "uploadProgress", "number"
        attribute "deleteProgress", "number"
        
        // File statistics
        attribute "fileTypes", "string"
        attribute "largestFile", "string"
        attribute "newestFile", "string"
        attribute "oldestFile", "string"
        
        // Status attributes
        attribute "status", "string"
        attribute "errorMessage", "string"
        
        // Commands
        command "refresh"
        command "listFiles", [[name: "folder", type: "STRING", description: "Folder name (optional)"]]
        command "listFolders"
        command "uploadFile", [[name: "filename", type: "STRING", description: "File name"], [name: "content", type: "STRING", description: "Base64 encoded content"]]
        command "deleteFile", [[name: "filename", type: "STRING", description: "File name to delete"]]
        command "createFolder", [[name: "folderName", type: "STRING", description: "Folder name to create"]]
        command "deleteFolder", [[name: "folderName", type: "STRING", description: "Folder name to delete"]]
        command "downloadFile", [[name: "filename", type: "STRING", description: "File name to download"]]
        command "getStats"
        command "clearError"
    }
}

def installed() {
    log.debug "File Manager Device installed"
    initialize()
}

def updated() {
    log.debug "File Manager Device updated"
    initialize()
}

def uninstalled() {
    log.debug "File Manager Device uninstalled"
}

def initialize() {
    log.debug "Initializing File Manager Device"
    sendEvent(name: "status", value: "initialized")
    sendEvent(name: "currentFolder", value: "")
    sendEvent(name: "lastOperation", value: "initialized")
    sendEvent(name: "lastOperationTime", value: new Date().format("yyyy-MM-dd HH:mm:ss"))
    refresh()
}

def refresh() {
    log.debug "Refreshing file manager device"
    try {
        sendEvent(name: "status", value: "refreshing")
        
        // Get file statistics
        def stats = parent.getFileStats()
        sendEvent(name: "totalFiles", value: stats.totalFiles)
        sendEvent(name: "totalSize", value: stats.totalSize)
        sendEvent(name: "totalFolders", value: stats.folders)
        sendEvent(name: "fileTypes", value: stats.fileTypes.toString())
        
        // Get current folder files (only show files, not folders in main list)
        def currentFolder = device.currentValue("currentFolder") ?: ""
        def files = currentFolder ? parent.getFilesInFolder(currentFolder) : parent.getFilteredFiles().findAll { !it.name.startsWith(parent.state.folderPrefix) }
        
        // Update file list (limit to first 10 for display)
        def fileList = files.take(10).collect { file ->
            "${file.name} (${formatFileSize(file.size)})"
        }.join(", ")
        sendEvent(name: "fileList", value: fileList)
        
        // Get folders
        def folders = parent.getFolders()
        def folderList = folders.collect { it.name }.join(", ")
        sendEvent(name: "folderList", value: folderList)
        
        // Find largest, newest, and oldest files
        if (files) {
            def largestFile = files.max { it.size ?: 0 }
            def newestFile = files.max { it.lastModified ?: 0 }
            def oldestFile = files.min { it.lastModified ?: 0 }
            
            sendEvent(name: "largestFile", value: "${largestFile.name} (${formatFileSize(largestFile.size)})")
            sendEvent(name: "newestFile", value: "${newestFile.name} (${new Date(newestFile.lastModified).format("yyyy-MM-dd HH:mm:ss")})")
            sendEvent(name: "oldestFile", value: "${oldestFile.name} (${new Date(oldestFile.lastModified).format("yyyy-MM-dd HH:mm:ss")})")
        }
        
        sendEvent(name: "status", value: "ready")
        sendEvent(name: "lastOperation", value: "refresh")
        sendEvent(name: "lastOperationTime", value: new Date().format("yyyy-MM-dd HH:mm:ss"))
        
        log.debug "Refresh completed: ${stats.totalFiles} files, ${stats.totalSize} bytes"
        
    } catch (Exception e) {
        log.error "Error during refresh: ${e.message}"
        sendEvent(name: "status", value: "error")
        sendEvent(name: "errorMessage", value: e.message)
    }
}

def listFiles(String folder = "") {
    log.debug "Listing files in folder: ${folder}"
    try {
        sendEvent(name: "status", value: "listing")
        sendEvent(name: "currentFolder", value: folder)
        
        def files = folder ? parent.getFilesInFolder(folder) : parent.getFilteredFiles().findAll { !it.name.startsWith(parent.state.folderPrefix) }
        def fileList = files.collect { file ->
            "${file.name} (${formatFileSize(file.size)})"
        }.join(", ")
        
        sendEvent(name: "fileList", value: fileList)
        sendEvent(name: "status", value: "ready")
        sendEvent(name: "lastOperation", value: "listFiles")
        sendEvent(name: "lastOperationTime", value: new Date().format("yyyy-MM-dd HH:mm:ss"))
        
        log.debug "Listed ${files.size()} files in folder: ${folder}"
        
    } catch (Exception e) {
        log.error "Error listing files: ${e.message}"
        sendEvent(name: "status", value: "error")
        sendEvent(name: "errorMessage", value: e.message)
    }
}

def listFolders() {
    log.debug "Listing folders"
    try {
        sendEvent(name: "status", value: "listing")
        
        def folders = parent.getFolders()
        def folderList = folders.collect { it.name }.join(", ")
        
        sendEvent(name: "folderList", value: folderList)
        sendEvent(name: "status", value: "ready")
        sendEvent(name: "lastOperation", value: "listFolders")
        sendEvent(name: "lastOperationTime", value: new Date().format("yyyy-MM-dd HH:mm:ss"))
        
        log.debug "Listed ${folders.size()} folders"
        
    } catch (Exception e) {
        log.error "Error listing folders: ${e.message}"
        sendEvent(name: "status", value: "error")
        sendEvent(name: "errorMessage", value: e.message)
    }
}

def uploadFile(String filename, String content) {
    log.debug "Uploading file: ${filename}"
    try {
        sendEvent(name: "status", value: "uploading")
        sendEvent(name: "uploadProgress", value: 0)
        
        // Decode base64 content
        byte[] fileContent = content.decodeBase64()
        
        // Upload with progress simulation
        sendEvent(name: "uploadProgress", value: 50)
        
        def success = parent.uploadFileWithOrganization(filename, fileContent)
        
        if (success) {
            sendEvent(name: "uploadProgress", value: 100)
            sendEvent(name: "status", value: "ready")
            sendEvent(name: "lastOperation", value: "uploadFile")
            sendEvent(name: "lastOperationTime", value: new Date().format("yyyy-MM-dd HH:mm:ss"))
            
            // Refresh file list
            refresh()
            
            log.debug "File uploaded successfully: ${filename}"
        } else {
            throw new Exception("Upload failed")
        }
        
    } catch (Exception e) {
        log.error "Error uploading file: ${e.message}"
        sendEvent(name: "status", value: "error")
        sendEvent(name: "errorMessage", value: e.message)
        sendEvent(name: "uploadProgress", value: 0)
    }
}

def deleteFile(String filename) {
    log.debug "Deleting file: ${filename}"
    try {
        sendEvent(name: "status", value: "deleting")
        sendEvent(name: "deleteProgress", value: 0)
        
        def success = parent.deleteHubFile(filename)
        
        if (success) {
            sendEvent(name: "deleteProgress", value: 100)
            sendEvent(name: "status", value: "ready")
            sendEvent(name: "lastOperation", value: "deleteFile")
            sendEvent(name: "lastOperationTime", value: new Date().format("yyyy-MM-dd HH:mm:ss"))
            
            // Refresh file list
            refresh()
            
            log.debug "File deleted successfully: ${filename}"
        } else {
            throw new Exception("File not found or could not be deleted")
        }
        
    } catch (Exception e) {
        log.error "Error deleting file: ${e.message}"
        sendEvent(name: "status", value: "error")
        sendEvent(name: "errorMessage", value: e.message)
        sendEvent(name: "deleteProgress", value: 0)
    }
}

def createFolder(String folderName) {
    log.debug "Creating folder: ${folderName}"
    try {
        sendEvent(name: "status", value: "creating")
        
        def success = parent.createFolder(folderName)
        
        if (success) {
            sendEvent(name: "status", value: "ready")
            sendEvent(name: "lastOperation", value: "createFolder")
            sendEvent(name: "lastOperationTime", value: new Date().format("yyyy-MM-dd HH:mm:ss"))
            
            // Refresh folder list
            listFolders()
            
            log.debug "Folder created successfully: ${folderName}"
        } else {
            throw new Exception("Failed to create folder")
        }
        
    } catch (Exception e) {
        log.error "Error creating folder: ${e.message}"
        sendEvent(name: "status", value: "error")
        sendEvent(name: "errorMessage", value: e.message)
    }
}

def deleteFolder(String folderName) {
    log.debug "Deleting folder: ${folderName}"
    try {
        sendEvent(name: "status", value: "deleting")
        
        def deletedCount = parent.deleteFolder(folderName)
        
        if (deletedCount >= 0) {
            sendEvent(name: "status", value: "ready")
            sendEvent(name: "lastOperation", value: "deleteFolder")
            sendEvent(name: "lastOperationTime", value: new Date().format("yyyy-MM-dd HH:mm:ss"))
            
            // Refresh lists
            refresh()
            
            log.debug "Folder deleted successfully: ${folderName} (${deletedCount} files removed)"
        } else {
            throw new Exception("Failed to delete folder")
        }
        
    } catch (Exception e) {
        log.error "Error deleting folder: ${e.message}"
        sendEvent(name: "status", value: "error")
        sendEvent(name: "errorMessage", value: e.message)
    }
}

def downloadFile(String filename) {
    log.debug "Downloading file: ${filename}"
    try {
        sendEvent(name: "status", value: "downloading")
        
        byte[] content = parent.downloadHubFile(filename)
        
        if (content) {
            def publicUrl = parent.getPublicUrl(filename)
            
            sendEvent(name: "status", value: "ready")
            sendEvent(name: "lastOperation", value: "downloadFile")
            sendEvent(name: "lastOperationTime", value: new Date().format("yyyy-MM-dd HH:mm:ss"))
            
            log.debug "File downloaded successfully: ${filename} (${content.length} bytes)"
            log.info "Download URL: ${publicUrl}"
            
            return publicUrl
        } else {
            throw new Exception("File not found")
        }
        
    } catch (Exception e) {
        log.error "Error downloading file: ${e.message}"
        sendEvent(name: "status", value: "error")
        sendEvent(name: "errorMessage", value: e.message)
        return null
    }
}

def getStats() {
    log.debug "Getting file statistics"
    try {
        sendEvent(name: "status", value: "calculating")
        
        def stats = parent.getFileStats()
        
        sendEvent(name: "totalFiles", value: stats.totalFiles)
        sendEvent(name: "totalSize", value: stats.totalSize)
        sendEvent(name: "totalFolders", value: stats.folders)
        sendEvent(name: "fileTypes", value: stats.fileTypes.toString())
        
        sendEvent(name: "status", value: "ready")
        sendEvent(name: "lastOperation", value: "getStats")
        sendEvent(name: "lastOperationTime", value: new Date().format("yyyy-MM-dd HH:mm:ss"))
        
        log.debug "Statistics updated: ${stats.totalFiles} files, ${formatFileSize(stats.totalSize)}"
        
    } catch (Exception e) {
        log.error "Error getting statistics: ${e.message}"
        sendEvent(name: "status", value: "error")
        sendEvent(name: "errorMessage", value: e.message)
    }
}

def clearError() {
    log.debug "Clearing error state"
    sendEvent(name: "status", value: "ready")
    sendEvent(name: "errorMessage", value: "")
    sendEvent(name: "lastOperation", value: "clearError")
    sendEvent(name: "lastOperationTime", value: new Date().format("yyyy-MM-dd HH:mm:ss"))
}

// Utility method to format file sizes
private String formatFileSize(Long bytes) {
    if (bytes == null || bytes == 0) return "0 B"
    
    def units = ['B', 'KB', 'MB', 'GB', 'TB']
    def unitIndex = 0
    def size = bytes.toDouble()
    
    while (size >= 1024 && unitIndex < units.size() - 1) {
        size /= 1024
        unitIndex++
    }
    
    return "${size.round(2)} ${units[unitIndex]}"
}
