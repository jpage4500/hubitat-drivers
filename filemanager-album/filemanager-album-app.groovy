// hubitat start
// hub: 192.168.0.200
// type: app
// id: 885
// hubitat end

definition(
    name: "File Manager Album",
    namespace: "jpage4500",
    author: "Joe Page",
    description: "Create a device and set it with an image URL from File Manager",
    oauth: true,
    iconUrl: '',
    iconX2Url: '',
    iconX3Url: ''
)

preferences {
    page(name: "mainPage", title: "Album Settings", install: true, uninstall: true) {
        section("Settings") {
            input "refreshInterval", "number", title: "Refresh interval (minutes)", defaultValue: 15, required: true
            input 'prefix', 'text', title: 'image name prefix', required: false, defaultValue: '', submitOnChange: true
        }
    }
}

mappings {
    path("/image/:filename") {
        action:
        [GET: "serveNamedImage"]
    }
}

def installed() {
    log.debug "Installed"
    initialize()
}

def updated() {
    initialize()
}

def initialize() {
    log.debug "Initializing with refreshInterval: ${refreshInterval} minutes"

    if (!getChildDevice("FileManagerDevice")) {
        addChildDevice("jpage4500", "File Manager Device", "FileManagerDevice", [label: "Image Viewer", name: "FileManagerDevice"])
    }

    // schedule next update
    unschedule()
    schedule("0 */${refreshInterval} * ? * *", getNextPhoto)

    getNextPhoto()
}

def updateImage() {
    def imageFiles = listImageFiles()
    if (!imageFiles || imageFiles.isEmpty()) {
        log.warn "No image files found in File Manager."
        state.total = 0
        return
    }
    state.total = imageFiles.size()

    def index = state.currentIndex ?: 0
    if (index >= state.total) {
        index = 0
    }

    def filename = imageFiles[index % state.total]
    def publicUrl = getPublicUrl(filename)
    def localUrl = getLocalUrl(filename)

    log.debug "updateImage: ${filename}, index:${index}, total:${state.total}"

    def child = getChildDevice("FileManagerDevice")
    child?.sendEvent(name: 'image', value: localUrl)
    child?.sendEvent(name: 'remoteImage', value: publicUrl)
    child?.sendEvent(name: 'name', value: filename)
    child?.sendEvent(name: 'index', value: index)
    child?.sendEvent(name: 'total', value: state.total)
    child?.sendEvent(name: "lastUpdatedMs", value: now())
    child?.sendEvent(name: 'html', value: '<div style="box-sizing: content-box"><img style="height: 100%; width: 100%; object-fit: contain" src="' + "${localUrl}" + '" /></div>')
}

def listImageFiles() {
    def allFiles = getHubFiles()
    //log.debug "Found ${allFiles.size()} files ${allFiles}"
    // filter images
    def imageFiles = allFiles.findAll { it.name ==~ /(?i).*\.(jpg|jpeg|png|gif)$/ }

    if (prefix && prefix.length() > 0) {
        imageFiles = imageFiles.findAll { it.name ==~ /(?i)${prefix}.*/ }
    }

    return imageFiles*.name.sort()
}

def getPublicUrl(String filename) {
    def safeName = safeName(filename)
    def token = state.accessToken ?: createAccessToken()
    return "${fullApiServerUrl("image/${safeName}")}" + "?access_token=${token}"
}

// local URL: http://IP/local/FILENAME
def getLocalUrl(String filename) {
    def safeName = safeName(filename)
    def hub = location.hubs[0]
    def localIp = hub.getDataValue("localIP")
    return "http://${localIp}/local/${safeName}"
}

def getNextPhoto() {
    def index = (state.currentIndex ?: 0) + 1
    def total = state.total ?: 0
    if (index >= total) {
        // reset to first image
        index = 0
    }
    state.currentIndex = index
    log.debug "getNextPhoto: ${index}, total:${total}"
    updateImage()
}

def getPrevPhoto() {
    def index = (state.currentIndex ?: 0) - 1
    def total = state.total ?: 0
    if (index < 0) {
        // get last image
        if (total > 0) index = total - 1
        else index = 0
    }
    state.currentIndex = index
    log.debug "getPrevPhoto: ${index}, total:${total}"
    updateImage()
}

def pausePhotos() {
    log.debug "pausePhotos: ${state.currentIndex}"
    unschedule()
}

def resume() {
    log.debug "resume: ${state.currentIndex}"
    initialize()
}

def serveNamedImage() {
    def filename = params.filename
    if (!filename) {
        render status: 400, text: "Missing filename"
        return
    }

    def safeName = safeName(filename)
    log.debug "Serving image: ${filename}, safePath: ${safeName}"

    byte[] bytes = downloadHubFile(safeName)

    if (!bytes) {
        render status: 404, text: "File not found: ${safeName}"
        return
    }

    def ext = safeName.tokenize('.').last().toLowerCase()
    def contentType = [
        jpg : "image/jpeg",
        jpeg: "image/jpeg",
        png : "image/png",
        gif : "image/gif"
    ][ext] ?: "application/octet-stream"

    log.debug "Serving image ${bytes.length} bytes, type: ${contentType}"

    render contentType: contentType, data: bytes
}

static String safeName(String filename) {
    return filename?.replaceAll("[^a-zA-Z0-9_.\\-]", "")
}