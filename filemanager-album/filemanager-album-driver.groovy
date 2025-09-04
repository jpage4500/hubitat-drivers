// hubitat start
// hub: 192.168.0.200
// type: device
// id: 1711
// hubitat end

metadata {
    definition(name: "File Manager Device", namespace: "jpage4500", author: "Joe Page") {
        capability "Image Capture"

        attribute "image", "string"
        attribute "remoteImage", "string"
        attribute "name", "string"
        attribute "index", "number"
        attribute "total", "number"
        attribute 'html', 'string'
        attribute "lastUpdatedMs", "number"

        command 'next'
        command 'previous'
        command 'pause'
        command 'resume'

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
