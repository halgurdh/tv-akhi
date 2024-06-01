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
    let selectionStarted = selection.rangeCount !== 0;
    if (selectionStarted && selection.getRangeAt(0).startContainer.selectionStart === undefined &&
        selection.getRangeAt(0).endContainer.selectionStart === undefined) {
        //there are some other selections, not started by this extension, clear them
        selectionStarted = false;
        selection.removeAllRanges();
    }
    if (!selectionStarted) {
        node.selectionStart = offset;
        let range = document.createRange();
        range.setStart(node, offset);
        range.setEnd(node, offset);
        selection.addRange(range);
    } else {
        let range = selection.getRangeAt(0);
        if (range.startContainer.selectionStart !== undefined && range.startContainer.selectionStart === range.startOffset) {
            range.collapse(true);
        } else {
            range.collapse(false);
        }

        switch (range.comparePoint(node, offset)) {
            case -1: {
                range.setStart(node, offset);
                break;
            }
            case 1: {
                range.setEnd(node, offset);
                break;
            }
        }

        selection.removeAllRanges();
        selection.addRange(range);
    }
  }
}

function TVBRO_clearSelection() {
    let selection = window.getSelection();
    let range = selection.getRangeAt(0);
    delete range.startContainer.selectionStart;
    delete range.endContainer.selectionEnd;
    selection.removeAllRanges();
}

function TVBRO_processSelection() {
    let selection = window.getSelection();
    let selectedText = selection.toString().trim();
    let editable = false;
    if (selection.rangeCount > 0) {
        let range = selection.getRangeAt(0);
        let node = range.startContainer;
        while (node) {
            if (node.tagName && (node.tagName.toLowerCase() === 'input' || node.tagName.toLowerCase() === 'textarea')) {
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