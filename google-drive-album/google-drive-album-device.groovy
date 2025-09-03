// hubitat start
// hub: 192.168.0.200
// type: device
// id: 1713
// hubitat end

/**
 * Device Driver - Google Drive Image Viewer
 */

metadata {
    definition(name: 'Google Drive Album Device', namespace: 'jpage4500', author: 'Joe Page') {
        capability "Image Capture"

        attribute 'image', 'string'
        attribute 'token', 'string'
        attribute 'name', 'string'
        attribute 'mediaType', 'string'
        attribute 'html', 'string'
        attribute "lastUpdatedMs", "number"

        command 'next'
        command 'previous'
        command 'pause'
        command 'resume'
    }

    preferences {
        input name: "debugOutput", type: "bool", title: "Enable Debug Logging?", defaultValue: false
    }
}

def installed() {
    initialize()
}

def updated() {
    initialize()
}

def uninstalled() {
}

def initialize() {
}

def refresh() {
    parent.getNextPhoto()
}

def next() {
    parent.getNextPhoto()
}

def previous() {
    parent.getPrevPhoto()
}

def pause() {
    parent.pausePhotos()
}

def resume() {
    parent.getNextPhoto()
}
