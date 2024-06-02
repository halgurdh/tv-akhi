package com.phlox.tvwebbrowser.webengine.webview

object Scripts {
    const val INITIAL_SCRIPT = """
window.addEventListener("touchstart", function(e) {
    window.TVBRO_activeElement = e.target;
    window.TVBRO_touchStartX = e.touches[0].clientX;
    window.TVBRO_touchStartY = e.touches[0].clientY;
});

function TVBRO_updateSelection(x, y, w, h) {
  let pageX = x * (window.innerWidth / window.visualViewport.scale / w) + window.visualViewport.offsetLeft;
  let pageY = y * (window.innerHeight / window.visualViewport.scale / h) + window.visualViewport.offsetTop;
  
  if (document.caretRangeFromPoint) {
    let caretRange = document.caretRangeFromPoint(pageX, pageY);
    let node = caretRange.startContainer;
    let offset = caretRange.startOffset;
  
    let selection = window.getSelection();
    if (selection.anchorNode === null || selection.anchorNode === undefined) {
        selection.setPosition(node, offset);
    } else {
        selection.extend(node, offset);
    }
  }
}

function TVBRO_clearSelection() {
    let selection = window.getSelection();
    selection.removeAllRanges();
}

function TVBRO_processSelection() {
    let selection = window.getSelection();
    let selectedText = selection.toString().trim();
    let editable = false;
    if (selection.anchorNode) {
        let node = selection.anchorNode;
        while (node) {
            if (node.isContentEditable) {
                editable = true;
                break;
            }
            node = node.parentNode;
        }
    }
    return JSON.stringify({selectedText: selectedText, editable: editable});
}
"""

    const val LONG_PRESS_SCRIPT = """
var element = window.TVBRO_activeElement;
if (element != null) {
  if ('A' == element.tagName) {
    element.protocol+'//'+element.host+element.pathname+element.search+element.hash;
  } else if (element.src != null) {
    element.src;
  }
}"""
}