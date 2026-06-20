import os

output_file = "photowconnect_merged.txt"
# Add or remove file extensions as needed
extensions = ('.kt', '.java', '.xml', '.gradle', '.kts') 

with open(output_file, 'w', encoding='utf-8') as outfile:
    for root, dirs, files in os.walk('.'):
        # Ignore generated build files and hidden IDE folders
        if 'build' in root or '.git' in root or '.idea' in root:
            continue
            
        for file in files:
            if file.endswith(extensions):
                filepath = os.path.join(root, file)
                outfile.write(f"\n\n{'='*50}\n")
                outfile.write(f"FILE: {filepath}\n")
                outfile.write(f"{'='*50}\n\n")
                
                try:
                    with open(filepath, 'r', encoding='utf-8') as infile:
                        outfile.write(infile.read())
                except Exception:
                    outfile.write("[Could not read this file]\n")

print(f"Done! Upload {output_file} to the chat.")