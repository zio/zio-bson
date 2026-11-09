const sidebars = {
  sidebar: [
    {
      type: "category",
      label: "ZIO Bson",
      collapsed: false,
      link: { type: "doc", id: "index" },
      items: [
        {
          type: "category",
          label: "Tutorial",
          collapsed: false,
          items: ["tutorial-store-and-load"]
        },
        {
          type: "category",
          label: "How-to Guides",
          collapsed: false,
          items: [
            "how-to-customize-field-names-and-discriminators",
            "how-to-write-a-codec-by-hand",
            "how-to-use-with-mongodb-driver"
          ]
        },
        {
          type: "category",
          label: "Reference",
          collapsed: false,
          items: [
            "bson-encoder",
            "bson-decoder",
            "bson-codec",
            "bson-codec-configuration",
            "bson-field-codecs",
            "bson-errors",
            "types"
          ]
        }
      ]
    }
  ]
};

module.exports = sidebars;
