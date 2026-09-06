# Excalidraw JSON Schema Reference

## Element Types

### Rectangle
```json
{
  "type": "rectangle", "id": "id", "x": 100, "y": 100,
  "width": 200, "height": 80,
  "strokeColor": "#1e40af", "backgroundColor": "#dbeafe",
  "fillStyle": "solid", "strokeWidth": 2, "roughness": 1,
  "opacity": 100, "roundness": { "type": 3 }
}
```

### Text
```json
{
  "type": "text", "id": "id", "x": 150, "y": 130,
  "text": "Content", "fontSize": 20, "fontFamily": 5,
  "textAlign": "center", "verticalAlign": "middle",
  "strokeColor": "#1e40af", "backgroundColor": "transparent",
  "lineHeight": 1.25, "autoResize": true, "originalText": "Content"
}
```

### Arrow
```json
{
  "type": "arrow", "id": "id", "x": 300, "y": 140,
  "width": 100, "height": 0,
  "points": [[0, 0], [100, 0]],
  "strokeColor": "#374151", "strokeWidth": 2,
  "startArrowhead": null, "endArrowhead": "arrow"
}
```

### Ellipse
```json
{
  "type": "ellipse", "id": "id", "x": 100, "y": 100,
  "width": 120, "height": 120,
  "strokeColor": "#10b981", "backgroundColor": "#d1fae5",
  "fillStyle": "solid"
}
```

### Diamond
```json
{
  "type": "diamond", "id": "id", "x": 100, "y": 100,
  "width": 150, "height": 100,
  "strokeColor": "#f59e0b", "backgroundColor": "#fef3c7",
  "fillStyle": "solid"
}
```

## Font Family Values

| Value | Font Name |
|-------|-----------|
| 1 | Virgil (hand-drawn) |
| 2 | Helvetica |
| 3 | Cascadia |
| 4 | Assistant |
| 5 | Excalifont (recommended) |

## Fill Styles

- `solid` - Solid fill
- `hachure` - Hatched lines
- `cross-hatch` - Cross-hatched
- `dots` - Dotted pattern

## Roundness Types

- `{ "type": 1 }` - Sharp corners
- `{ "type": 2 }` - Slight rounding
- `{ "type": 3 }` - Full rounding (recommended)

## Element Binding

Connect text to container:
```json
{ "type": "rectangle", "id": "container-id", "boundElements": [{ "id": "text-id", "type": "text" }] }
{ "type": "text", "id": "text-id", "containerId": "container-id" }
```

Connect arrows to shapes:
```json
{
  "type": "arrow",
  "startBinding": { "elementId": "source-id", "focus": 0, "gap": 5 },
  "endBinding": { "elementId": "target-id", "focus": 0, "gap": 5 }
}
```
