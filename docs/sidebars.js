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
    "tutorial-store-and-load",
    {
      type: "category",
      label: "How-to Guides",
      collapsed: true,
      items: [
        "how-to-customize-field-names-and-discriminators",
        "how-to-write-a-codec-by-hand",
        "how-to-use-with-mongodb-driver"
      ]
    }
  ]
};

module.exports = sidebars;
