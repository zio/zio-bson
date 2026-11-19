const sidebars = {
  sidebar: [
    "index",
    "getting-started",
    {
      type: "category",
      label: "Reference",
      collapsed: false,
      items: [
        "bson-encoder",
        "bson-decoder",
        "bson-codec",
        "bson-codec-configuration",
        "bson-derivation",
        "bson-builder",
        "bson-field-codecs",
        "bson-errors",
        "types"
      ]
    },
    {
      type: "category",
      label: "Tutorials",
      collapsible: false,
      collapsed: false,
      items: ["tutorial-store-and-load", "tutorial-model-with-sealed-traits"]
    },
    {
      type: "category",
      label: "How-to Guides",
      collapsed: true,
      items: [
        "how-to-customize-field-names-and-discriminators",
        "how-to-handle-optional-fields-and-defaults",
        "how-to-store-dates-and-decimals",
        "how-to-write-a-codec-by-hand",
        "how-to-use-with-mongodb-driver"
      ]
    },
    "resources"
  ]
};

module.exports = sidebars;
